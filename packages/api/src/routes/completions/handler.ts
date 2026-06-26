import type { Context } from "elysia"
import { env } from "../../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
	unprefixToolName,
} from "../../converters/openai-to-kiro"
import { logRequest } from "../../logging"
import { collectResponse, createOpenAIStream } from "../../streaming"
import { generateCompletionId } from "../../utils/ids"
import { countTokens, estimateRequestTokens } from "../../utils/tokenizer"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
} from "../../virtual-keys"
import { getAuth, getClient, modelResolver } from "../shared"
import type { ChatCompletionChoice, ChatCompletionResponse } from "./types"

export async function handleChatCompletion(
	body: unknown,
	set: Context["set"],
	authResult: AuthResult,
	clientIp: string | undefined,
) {
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
	
	const auth = authResult
	const keyId =
		auth.type === "virtual_key" && auth.key ? auth.key.id : undefined
	
	if (auth.type === "virtual_key" && auth.key) {
		if (checkRateLimit(auth.key)) {
			logRequest({
				clientIp,
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
				clientIp,
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
	
	const resolvedModel = resolved.internalId
	
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
		return { error: { message: errorText, type: "upstream_error" } }
	}
	
	if (req.stream) {
		const inputTokens = estimateRequestTokens(req.messages, req.tools)
		const stream = createOpenAIStream(response, resolvedModel, inputTokens, (usage) => {
			if (auth.type === "virtual_key" && auth.key) {
				recordUsage(auth.key.id, usage.total_tokens)
			}
			logRequest({
				clientIp,
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
	const inputTokens = estimateRequestTokens(req.messages, req.tools)
	const completionTokens = countTokens(result.content)
	const totalTokens = inputTokens + completionTokens
	const usage = { prompt_tokens: inputTokens, completion_tokens: completionTokens, total_tokens: totalTokens }
	
	if (auth.type === "virtual_key" && auth.key) {
		recordUsage(auth.key.id, totalTokens)
	}
	
	logRequest({
		clientIp,
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
		usage,
	}
	
	return responseBody
}
