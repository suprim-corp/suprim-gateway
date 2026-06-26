import { Elysia } from "elysia"
import { env } from "../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
	type OpenAIMessage,
	type OpenAITool,
	unprefixToolName,
} from "../converters/openai-to-kiro"
import { logRequest } from "../logging"
import { logger } from "../logging/logger"
import { collectResponse } from "../streaming/converter"
import { createAnthropicStream } from "../streaming/anthropic-stream"
import { countTokens, estimateRequestTokens } from "../utils/tokenizer"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../virtual-keys"
import { getAuth, getClient, modelResolver } from "./openai"

export interface AnthropicContentBlock {
	type: string
	text?: string
	id?: string
	name?: string
	input?: unknown
	tool_use_id?: string
	content?: string | AnthropicContentBlock[]
	is_error?: boolean
	source?: { type: string; media_type?: string; data?: string; url?: string }
}

interface AnthropicMessage {
	role: "user" | "assistant"
	content: string | AnthropicContentBlock[]
}

interface AnthropicTool {
	name: string
	description?: string
	input_schema: Record<string, unknown>
}

interface AnthropicRequest {
	model: string
	messages: AnthropicMessage[]
	system?: string | Array<{ type: string; text: string }>
	max_tokens: number
	stream?: boolean
	temperature?: number
	top_p?: number
	top_k?: number
	stop_sequences?: string[]
	tools?: AnthropicTool[]
	tool_choice?: { type: string; name?: string }
}

function convertMessages(messages: AnthropicMessage[]): OpenAIMessage[] {
	const result: OpenAIMessage[] = []

	for (const msg of messages) {
		if (typeof msg.content === "string") {
			result.push({ role: msg.role, content: msg.content })
			continue
		}

		const textParts: string[] = []
		const toolCalls: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }> = []
		const toolResults: Array<{ tool_call_id: string; content: string }> = []

		for (const block of msg.content) {
			if (block.type === "text" && block.text) {
				textParts.push(block.text)
			} else if (block.type === "tool_use" && block.id && block.name) {
				toolCalls.push({
					id: block.id,
					type: "function",
					function: {
						name: block.name,
						arguments: typeof block.input === "string" ? block.input : JSON.stringify(block.input ?? {}),
					},
				})
			} else if (block.type === "tool_result" && block.tool_use_id) {
				let content = ""
				if (typeof block.content === "string") {
					content = block.content
				} else if (Array.isArray(block.content)) {
					content = block.content
						.filter((b) => b.type === "text" && b.text)
						.map((b) => b.text)
						.join("")
				}
				toolResults.push({ tool_call_id: block.tool_use_id, content })
			}
		}

		if (msg.role === "assistant") {
			const m: OpenAIMessage = {
				role: "assistant",
				content: textParts.join("") || null,
			}
			if (toolCalls.length) m.tool_calls = toolCalls
			result.push(m)
		} else if (toolResults.length) {
			// User message containing tool_results — emit assistant tool_calls stub if needed, then tool messages
			if (textParts.length) {
				result.push({ role: "user", content: textParts.join("") })
			}
			for (const tr of toolResults) {
				result.push({ role: "tool", content: tr.content, tool_call_id: tr.tool_call_id })
			}
		} else {
			result.push({ role: "user", content: textParts.join("") })
		}
	}

	return result
}

function convertTools(tools: AnthropicTool[]): OpenAITool[] {
	return tools.map((t) => ({
		type: "function" as const,
		function: {
			name: t.name,
			description: t.description ?? "",
			parameters: t.input_schema,
		},
	}))
}

export const anthropicRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers, request }) => {
		const authResult = await resolveAuth(
			headers["x-api-key"] ?? headers.authorization,
		)
		const clientIp = headers["x-forwarded-for"]?.split(",")[0]?.trim()
			?? headers["x-real-ip"]
			?? new URL(request.url).hostname
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

		// Convert Anthropic messages → OpenAI format
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
