import { Elysia } from "elysia"
import { env } from "../../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
} from "../../converters/openai-to-kiro"
import { logRequest } from "../../logging"
import { logger } from "../../logging/logger"
import { collectResponse } from "../../streaming"
import { countTokens, estimateRequestTokens } from "../../utils/tokenizer"
import {
	type AuthResult,
	checkKeyBudget,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../../virtual-keys"
import { getAuth, getClient, modelResolver } from "../shared"
import { inputToMessages, type ResponsesRequest } from "./convert"
import { createResponsesStream } from "./stream"

export const responsesRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers, request, server }) => {
		const authResult = await resolveAuth(
			headers.authorization ?? headers["x-api-key"],
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
				error: {
					message: "Invalid or missing API Key",
					type: "auth_error",
				},
			}
		}
	})
	.post("/responses", async ({ body, set, authResult, clientIp }) => {
		const startTime = Date.now()
		const req = body as ResponsesRequest

		if (!req.model || !req.input) {
			set.status = 400
			return {
				error: {
					message: "model and input are required",
					type: "invalid_request_error",
				},
			}
		}

		const messages = inputToMessages(req.input)
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
				set.status = 429
				return {
					error: {
						message: "Rate limit exceeded",
						type: "rate_limit_error",
					},
				}
			}
			const budget = checkKeyBudget(auth.key)
			if (!budget.allowed) {
				logRequest({ clientIp, virtualKeyId: keyId, model: req.model, requestedModel: req.model, status: 429, streaming: req.stream, latencyMs: Date.now() - startTime, errorMessage: budget.reason })
				set.status = 429
				return {
					error: {
						message: budget.reason,
						type: "budget_exceeded",
					},
				}
			}
			if (!checkModelAccess(auth.key, req.model)) {
				set.status = 403
				return {
					error: {
						message: `Model '${req.model}' not allowed for this key`,
						type: "permission_error",
					},
				}
			}
		}

		const chatReq: ChatCompletionRequest = {
			model: req.model,
			messages,
			stream: req.stream ?? false,
			temperature: req.temperature,
			max_tokens: req.max_output_tokens,
			tools: req.tools as ChatCompletionRequest["tools"],
		}

		const kiroAuth = getAuth()
		const client = getClient()
		const payload = buildKiroPayload(chatReq, kiroAuth)
		const lastMsg = payload.conversationState.currentMessage.userInputMessage
		logger.debug(`[Responses] tools=${req.tools?.length ?? 0}, messages=${messages.length}, kiroTools=${lastMsg.userInputMessageContext?.tools?.length ?? 0}, history=${payload.conversationState.history?.length ?? 0}, currentContent=${lastMsg.content.length}c, toolResults=${lastMsg.userInputMessageContext?.toolResults?.length ?? 0}`)
		const url = `${kiroAuth.apiHost}/generateAssistantResponse`

		const response = await client.request({
			method: "POST",
			url,
			body: payload,
			stream: req.stream ?? false,
		})

		const resolvedModel = resolved.internalId
		const responseId = `resp_${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`

		if (response.status !== 200) {
			const errorText = await response.text()
			const mappedStatus = response.status >= 500 ? 502 : response.status
			logRequest({ clientIp,
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
			const inputTokens = estimateRequestTokens(messages, req.tools as ChatCompletionRequest["tools"])
			const stream = createResponsesStream(
				response,
				resolvedModel,
				responseId,
				inputTokens,
				(usage) => {
					if (auth.type === "virtual_key" && auth.key) {
						recordUsage(auth.key.id, usage.total_tokens)
					}
					logRequest({ clientIp,
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
				},
			)
			set.headers["content-type"] = "text/event-stream"
			set.headers["cache-control"] = "no-cache"
			set.headers.connection = "keep-alive"
			return new Response(stream as unknown as BodyInit)
		}

		const result = await collectResponse(response, resolvedModel)
		const inputTokens = estimateRequestTokens(messages, req.tools as ChatCompletionRequest["tools"])
		const completionTokens = countTokens(result.content)
		const totalTokens = inputTokens + completionTokens

		if (auth.type === "virtual_key" && auth.key) {
			recordUsage(auth.key.id, totalTokens)
		}

		logRequest({ clientIp,
			virtualKeyId: keyId,
			model: resolvedModel,
			requestedModel: req.model,
			status: 200,
			promptTokens: inputTokens,
			completionTokens,
			totalTokens,
			streaming: false,
			latencyMs: Date.now() - startTime,
		})

		return {
			id: responseId,
			object: "response",
			created_at: Math.floor(Date.now() / 1000),
			status: "completed",
			model: resolvedModel,
			output: [
				{
					type: "message",
					role: "assistant",
					content: [
						{
							type: "output_text",
							text: result.content || "",
						},
					],
				},
			],
			usage: {
				input_tokens: inputTokens,
				output_tokens: completionTokens,
				total_tokens: totalTokens,
			},
		}
	})
