import { Elysia } from "elysia"
import { env } from "../../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
	type OpenAIMessage,
	unprefixToolName,
} from "../../converters/openai-to-kiro"
import { logRequest } from "../../logging"
import { logger } from "../../logging/logger"
import { collectResponse } from "../../streaming/converter"
import { createAnthropicStream } from "../../streaming/anthropic-stream"
import { countTokens, estimateRequestTokens } from "../../utils/tokenizer"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../../virtual-keys"
import { getAuth, getClient, modelResolver } from "../shared"
import { type AnthropicContentBlock, type AnthropicRequest, convertMessages, convertTools } from "./convert"

export const anthropicRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers, request, server }) => {
		const authResult = await resolveAuth(
			headers["x-api-key"] ?? headers.authorization,
		)
		const clientIp = headers["x-forwarded-for"]?.split(",")[0]?.trim()
			?? headers["x-real-ip"]
			?? server?.requestIP(request)?.address
		return { authResult, clientIp }
	})
	.onBeforeHandle(({ authResult, set }) => {
		if (!authResult) {
			set.status = 401
			return {
				type: "error",
				error: {
					type: "authentication_error",
					message: "Invalid or missing API key",
				},
			}
		}
	})
	.post("/messages", async ({ body, set, authResult, clientIp }) => {
		const startTime = Date.now()
		const req = body as AnthropicRequest

		if (!req.model || !req.messages?.length || !req.max_tokens) {
			set.status = 400
			return {
				type: "error",
				error: {
					type: "invalid_request_error",
					message: "model, messages, and max_tokens are required",
				},
			}
		}

		const resolved = modelResolver.resolve(req.model)
		if (env.DISABLED_MODELS.includes(resolved.internalId)) {
			set.status = 403
			return {
				type: "error",
				error: {
					type: "permission_error",
					message: `Model '${req.model}' is disabled`,
				},
			}
		}

		const auth = authResult as AuthResult
		const keyId = auth.type === "virtual_key" && auth.key ? auth.key.id : undefined

		if (auth.type === "virtual_key" && auth.key) {
			if (checkRateLimit(auth.key)) {
				set.status = 429
				return {
					type: "error",
					error: { type: "rate_limit_error", message: "Rate limit exceeded" },
				}
			}
			if (!checkModelAccess(auth.key, req.model)) {
				set.status = 403
				return {
					type: "error",
					error: { type: "permission_error", message: `Model '${req.model}' not allowed for this key` },
				}
			}
		}

		const openaiMessages: OpenAIMessage[] = []

		if (req.system) {
			const systemText = typeof req.system === "string"
				? req.system
				: req.system.map((b) => b.text).join("\n\n")
			openaiMessages.push({ role: "system", content: systemText })
		}

		openaiMessages.push(...convertMessages(req.messages))

		const openaiTools = req.tools ? convertTools(req.tools) : undefined

		const chatReq: ChatCompletionRequest = {
			model: req.model,
			messages: openaiMessages,
			stream: req.stream ?? false,
			temperature: req.temperature,
			max_tokens: req.max_tokens,
			tools: openaiTools,
		}

		const kiroAuth = getAuth()
		const client = getClient()
		const payload = buildKiroPayload(chatReq, kiroAuth)
		const url = `${kiroAuth.apiHost}/generateAssistantResponse`

		logger.debug(`[Anthropic] messages=${openaiMessages.length}, tools=${req.tools?.length ?? 0}`)

		const response = await client.request({
			method: "POST",
			url,
			body: payload,
			stream: req.stream ?? false,
		})

		const resolvedModel = resolved.internalId
		const responseId = `msg_${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`

		if (response.status !== 200) {
			const errorText = await response.text()
			const mappedStatus = response.status >= 500 ? 502 : response.status
			logRequest({
				clientIp,
				virtualKeyId: keyId,
				model: resolvedModel,
				requestedModel: req.model,
				status: mappedStatus,
				streaming: req.stream,
				latencyMs: Date.now() - startTime,
				errorMessage: errorText.slice(0, 500),
			})
			set.status = mappedStatus
			return { type: "error", error: { type: "api_error", message: errorText } }
		}

		if (req.stream) {
			const inputTokens = estimateRequestTokens(openaiMessages, openaiTools)
			const stream = createAnthropicStream(
				response,
				resolvedModel,
				responseId,
				inputTokens,
				(usage) => {
					if (auth.type === "virtual_key" && auth.key) {
						recordUsage(auth.key.id, usage.input_tokens + usage.output_tokens)
					}
					logRequest({
						clientIp,
						virtualKeyId: keyId,
						model: resolvedModel,
						requestedModel: req.model,
						status: 200,
						promptTokens: usage.input_tokens,
						completionTokens: usage.output_tokens,
						totalTokens: usage.input_tokens + usage.output_tokens,
						streaming: true,
						latencyMs: Date.now() - startTime,
					})
				},
			)
			set.headers["content-type"] = "text/event-stream"
			set.headers["cache-control"] = "no-cache"
			set.headers.connection = "keep-alive"
			return new Response(stream as unknown as BodyInit)
		}

		// Non-streaming
		const result = await collectResponse(response, resolvedModel)
		const inputTokens = estimateRequestTokens(openaiMessages, openaiTools)
		const outputTokens = countTokens(result.content)

		if (auth.type === "virtual_key" && auth.key) {
			recordUsage(auth.key.id, inputTokens + outputTokens)
		}

		logRequest({
			clientIp,
			virtualKeyId: keyId,
			model: resolvedModel,
			requestedModel: req.model,
			status: 200,
			promptTokens: inputTokens,
			completionTokens: outputTokens,
			totalTokens: inputTokens + outputTokens,
			streaming: false,
			latencyMs: Date.now() - startTime,
		})

		const content: AnthropicContentBlock[] = []
		if (result.content) {
			content.push({ type: "text", text: result.content })
		}
		for (const tc of result.toolCalls) {
			let input: unknown = {}
			try { input = JSON.parse(tc.arguments) } catch { input = {} }
			content.push({
				type: "tool_use",
				id: tc.id,
				name: unprefixToolName(tc.name),
				input,
			})
		}

		return {
			id: responseId,
			type: "message",
			role: "assistant",
			content,
			model: resolvedModel,
			stop_reason: result.toolCalls.length ? "tool_use" : "end_turn",
			stop_sequence: null,
			usage: { input_tokens: inputTokens, output_tokens: outputTokens },
		}
	})
