import { FALLBACK_MODELS } from "@kiro-gateway/shared"
import { Elysia } from "elysia"
import type { AccountCredentialConfig } from "../auth"
import { KiroAuthManager } from "../auth"
import { env } from "../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
	unprefixToolName,
} from "../converters/openai-to-kiro"
import { KiroHttpClient } from "../kiro"
import { logRequest } from "../logging"
import { logger } from "../logging/logger"
import { ModelResolver } from "../models"
import { collectResponse, createOpenAIStream } from "../streaming"
import { generateCompletionId } from "../utils/ids"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../virtual-keys"

// ponytail: single account for MVP, multi-account in Phase 8
let authManager: KiroAuthManager | null = null
let httpClient: KiroHttpClient | null = null
const modelResolver = new ModelResolver()

const MODEL_CACHE_TTL = 300_000 // 5 min

function getAuth(): KiroAuthManager {
	if (!authManager) {
		const type: AccountCredentialConfig["type"] = env.KIRO_CLI_DB_FILE
			? "sqlite"
			: env.KIRO_CREDS_FILE
				? "json"
				: "refresh_token"
		const config: AccountCredentialConfig = {
			type,
			path: env.KIRO_CLI_DB_FILE ?? env.KIRO_CREDS_FILE ?? undefined,
			refresh_token: env.REFRESH_TOKEN ?? undefined,
			region: env.KIRO_REGION,
			api_region: env.KIRO_API_REGION,
			profile_arn: env.PROFILE_ARN,
		}
		authManager = new KiroAuthManager(config)
	}
	return authManager
}

function getClient(): KiroHttpClient {
	if (!httpClient) {
		httpClient = new KiroHttpClient(getAuth())
	}
	return httpClient
}

async function refreshModelCache() {
	try {
		const client = getClient()
		const auth = getAuth()
		logger.info(`Fetching models from ${auth.qHost}/ListAvailableModels`)
		const models = await client.listModels()
		const ids = models.map((m) => m.modelId)
		modelResolver.setCachedModels(ids)
		const disabled = ids.filter((id) => env.DISABLED_MODELS.includes(id))
		const active = ids.length - disabled.length
		logger.info(
			`Models: ${ids.length} fetched, ${active} active, ${disabled.length} disabled [${ids.join(", ")}]`,
		)
	} catch (e) {
		const fallbackIds = FALLBACK_MODELS.map((m) => m.modelId)
		modelResolver.setCachedModels(fallbackIds)
		logger.warn(
			`Failed to fetch models, using ${fallbackIds.length} fallbacks:`,
			e instanceof Error ? e.message : e,
		)
	}
}

// Periodic refresh (initial fetch triggered from index.ts)
export { getAuth, getClient, MODEL_CACHE_TTL, modelResolver, refreshModelCache }

interface ChatCompletionChoice {
	index: number
	message: {
		role: "assistant"
		content: string | null
		tool_calls?: Array<{
			id: string
			type: "function"
			function: { name: string; arguments: string }
		}>
	}
	finish_reason: "stop" | "tool_calls"
}

export interface ChatCompletionResponse {
	id: string
	object: "chat.completion"
	created: number
	model: string
	choices: ChatCompletionChoice[]
	usage: {
		prompt_tokens: number
		completion_tokens: number
		total_tokens: number
	}
}

export const openaiRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers }) => {
		const authResult = await resolveAuth(
			headers.authorization ?? headers["x-api-key"],
		)
		return { authResult }
	})
	.onBeforeHandle(({ authResult, set }) => {
		if (!authResult) {
			set.status = 401
			return {
				error: {
					message: "Invalid or missing API Key",
					type: "auth_error",
				},
			}
		}
	})
	.get("/models", () => {
		const allModels = modelResolver
			.getAvailableModels()
			.filter((id) => !env.DISABLED_MODELS.includes(id))

		return {
			object: "list",
			data: allModels.map((id) => ({
				id,
				object: "model",
				created: 1700000000,
				owned_by: "kiro",
			})),
		}
	})
	.post("/chat/completions", async ({ body, set, authResult }) => {
		const startTime = Date.now()
		const req = body as ChatCompletionRequest

		if (!req.model || !req.messages?.length) {
			set.status = 400
			return {
				error: {
					message: "model and messages are required",
					type: "invalid_request_error",
				},
			}
		}

		const resolved = modelResolver.resolve(req.model)
		if (env.DISABLED_MODELS.includes(resolved.internalId)) {
			set.status = 403
			return {
				error: {
					message: `Model '${req.model}' is disabled`,
					type: "model_error",
				},
			}
		}

		const auth = authResult as AuthResult
		const keyId =
			auth.type === "virtual_key" && auth.key ? auth.key.id : undefined

		if (auth.type === "virtual_key" && auth.key) {
			if (checkRateLimit(auth.key)) {
				logRequest({
					virtualKeyId: keyId,
					model: req.model,
					requestedModel: req.model,
					status: 429,
					streaming: req.stream,
					latencyMs: Date.now() - startTime,
					errorMessage: "Rate limit exceeded",
				})
				set.status = 429
				return {
					error: {
						message: "Rate limit exceeded",
						type: "rate_limit_error",
					},
				}
			}
			if (!checkModelAccess(auth.key, req.model)) {
				logRequest({
					virtualKeyId: keyId,
					model: req.model,
					requestedModel: req.model,
					status: 403,
					streaming: req.stream,
					latencyMs: Date.now() - startTime,
					errorMessage: "Model not allowed",
				})
				set.status = 403
				return {
					error: {
						message: `Model '${req.model}' not allowed for this key`,
						type: "permission_error",
					},
				}
			}
		}

		const kiroAuth = getAuth()
		const client = getClient()
		const payload = buildKiroPayload(req, kiroAuth)
		const url = `${kiroAuth.apiHost}/generateAssistantResponse`

		const response = await client.request({
			method: "POST",
			url,
			body: payload,
			stream: req.stream ?? false,
		})

		const resolvedModel = modelResolver.resolve(req.model).internalId

		if (response.status !== 200) {
			const errorText = await response.text()
			const mappedStatus = response.status >= 500 ? 502 : response.status
			logRequest({
				virtualKeyId: keyId,
				model: resolvedModel,
				requestedModel: req.model,
				status: mappedStatus,
				streaming: req.stream,
				latencyMs: Date.now() - startTime,
				errorMessage: errorText.slice(0, 500),
			})
			set.status = mappedStatus
			return { error: { message: errorText, type: "upstream_error" } }
		}

		if (req.stream) {
			const stream = createOpenAIStream(response, resolvedModel, (usage) => {
				if (auth.type === "virtual_key" && auth.key) {
					recordUsage(auth.key.id, usage.total_tokens)
				}
				logRequest({
					virtualKeyId: keyId,
					model: resolvedModel,
					requestedModel: req.model,
					status: 200,
					promptTokens: usage.prompt_tokens,
					completionTokens: usage.completion_tokens,
					totalTokens: usage.total_tokens,
					streaming: true,
					latencyMs: Date.now() - startTime,
				})
			})
			set.headers["content-type"] = "text/event-stream"
			set.headers["cache-control"] = "no-cache"
			set.headers.connection = "keep-alive"
			return new Response(stream as unknown as BodyInit)
		}

		const result = await collectResponse(response, resolvedModel)
		const completionId = generateCompletionId()

		if (auth.type === "virtual_key" && auth.key) {
			recordUsage(auth.key.id, result.usage.total_tokens)
		}

		logRequest({
			virtualKeyId: keyId,
			model: resolvedModel,
			requestedModel: req.model,
			status: 200,
			promptTokens: result.usage.prompt_tokens,
			completionTokens: result.usage.completion_tokens,
			totalTokens: result.usage.total_tokens,
			streaming: false,
			latencyMs: Date.now() - startTime,
		})

		const choice: ChatCompletionChoice = {
			index: 0,
			message: {
				role: "assistant",
				content: result.content || null,
				...(result.toolCalls.length && {
					tool_calls: result.toolCalls.map((tc) => ({
						id: tc.id,
						type: "function" as const,
						function: {
							name: unprefixToolName(tc.name),
							arguments: tc.arguments,
						},
					})),
				}),
			},
			finish_reason: result.toolCalls.length ? "tool_calls" : "stop",
		}

		const responseBody: ChatCompletionResponse = {
			id: completionId,
			object: "chat.completion",
			created: Math.floor(Date.now() / 1000),
			model: resolvedModel,
			choices: [choice],
			usage: result.usage,
		}

		return responseBody
	})
