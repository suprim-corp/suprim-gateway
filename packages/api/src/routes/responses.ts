import { Elysia } from "elysia"
import { env } from "../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
	unprefixToolName,
} from "../converters/openai-to-kiro"
import { AwsEventStreamParser, parseBracketToolCalls } from "../kiro/parser"
import type { ToolUseEvent } from "../kiro/types"
import { logRequest } from "../logging"
import { logger } from "../logging/logger"
import { collectResponse } from "../streaming/converter"
import { countTokens, estimateRequestTokens } from "../utils/tokenizer"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../virtual-keys"
import { getAuth, getClient, modelResolver } from "./openai"

interface ResponsesInputMessage {
	type?: "message"
	role: "user" | "assistant" | "system" | "developer"
	content: string | Array<{ type: string; text?: string }>
}

interface ResponsesInputFunctionCall {
	type: "function_call"
	id?: string
	call_id: string
	name: string
	arguments: string
}

interface ResponsesInputFunctionCallOutput {
	type: "function_call_output"
	call_id: string
	output: string
}

type ResponsesInput =
	| ResponsesInputMessage
	| ResponsesInputFunctionCall
	| ResponsesInputFunctionCallOutput

interface ResponsesRequest {
	model: string
	input: string | ResponsesInput[]
	stream?: boolean
	temperature?: number
	max_output_tokens?: number
	tools?: unknown[]
	tool_choice?: string
	reasoning?: { effort?: string }
}

function inputToMessages(
	input: string | ResponsesInput[],
): ChatCompletionRequest["messages"] {
	if (typeof input === "string") {
		return [{ role: "user", content: input }]
	}

	const messages: ChatCompletionRequest["messages"] = []
	let pendingToolCalls: Array<{
		id: string
		type: "function"
		function: { name: string; arguments: string }
	}> = []
	let pendingAssistantContent = ""

	for (const item of input) {
		if (item.type === "function_call") {
			pendingToolCalls.push({
				id: item.call_id,
				type: "function",
				function: { name: item.name, arguments: item.arguments },
			})
		} else if (item.type === "function_call_output") {
			if (pendingToolCalls.length) {
				messages.push({
					role: "assistant",
					content: pendingAssistantContent || "",
					tool_calls: pendingToolCalls,
				} as unknown as (typeof messages)[number])
				pendingToolCalls = []
				pendingAssistantContent = ""
			}
			messages.push({
				role: "tool",
				content: item.output,
				tool_call_id: item.call_id,
			} as unknown as (typeof messages)[number])
		} else {
			if (pendingToolCalls.length) {
				messages.push({
					role: "assistant",
					content: pendingAssistantContent || "",
					tool_calls: pendingToolCalls,
				} as unknown as (typeof messages)[number])
				pendingToolCalls = []
				pendingAssistantContent = ""
			}
			const content =
				typeof item.content === "string"
					? item.content
					: item.content
							.filter(
								(c) => c.type === "input_text" || c.type === "text",
							)
							.map((c) => c.text)
							.join("")
			const role = item.role === "developer" ? "system" : item.role

			// If assistant message, hold it — next items might be function_calls belonging to this turn
			if (role === "assistant") {
				// Flush any prior assistant content that had no tool calls
				if (pendingAssistantContent) {
					messages.push({ role: "assistant", content: pendingAssistantContent })
				}
				pendingAssistantContent = content
			} else {
				// Flush pending assistant if any
				if (pendingAssistantContent) {
					messages.push({ role: "assistant", content: pendingAssistantContent })
					pendingAssistantContent = ""
				}
				messages.push({ role, content })
			}
		}
	}

	// Flush remaining
	if (pendingToolCalls.length) {
		messages.push({
			role: "assistant",
			content: pendingAssistantContent || "",
			tool_calls: pendingToolCalls,
		} as unknown as (typeof messages)[number])
	} else if (pendingAssistantContent) {
		messages.push({ role: "assistant", content: pendingAssistantContent })
	}

	return messages
}

function createResponsesStream(
	kiroResponse: Response,
	model: string,
	responseId: string,
	inputTokens: number,
	onDone?: (usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number }) => void,
): ReadableStream<string> {
	const parser = new AwsEventStreamParser()
	let fullText = ""
	const toolCalls: ToolUseEvent[] = []
	let outputIndex = 0
	let textClosed = false

	function sse(event: { type: string; [k: string]: unknown }): string {
		return `data: ${JSON.stringify(event)}\n\n`
	}

	function closeTextPart(controller: ReadableStreamDefaultController<string>) {
		if (textClosed) return
		textClosed = true
		controller.enqueue(
			sse({
				type: "response.output_text.done",
				output_index: 0,
				content_index: 0,
				text: fullText,
			}),
		)
		controller.enqueue(
			sse({
				type: "response.content_part.done",
				output_index: 0,
				content_index: 0,
				part: { type: "output_text", text: fullText },
			}),
		)
		controller.enqueue(
			sse({
				type: "response.output_item.done",
				output_index: 0,
				item: {
					type: "message",
					id: `msg_${responseId.slice(5)}`,
					role: "assistant",
					content: [{ type: "output_text", text: fullText }],
				},
			}),
		)
		outputIndex = 1
	}

	function emitToolCall(
		controller: ReadableStreamDefaultController<string>,
		tc: ToolUseEvent,
	) {
		closeTextPart(controller)
		const name = unprefixToolName(tc.name)
		const itemId = `fc_${tc.id}`
		controller.enqueue(
			sse({
				type: "response.output_item.added",
				output_index: outputIndex,
				item: {
					type: "function_call",
					id: itemId,
					name,
					arguments: "",
					call_id: tc.id,
				},
			}),
		)
		// Chunk large arguments to avoid proxy/SSE buffer truncation
		const CHUNK_SIZE = 16_384
		const args = tc.arguments
		for (let i = 0; i < args.length; i += CHUNK_SIZE) {
			controller.enqueue(
				sse({
					type: "response.function_call_arguments.delta",
					item_id: itemId,
					call_id: tc.id,
					output_index: outputIndex,
					delta: args.slice(i, i + CHUNK_SIZE),
				}),
			)
		}
		controller.enqueue(
			sse({
				type: "response.function_call_arguments.done",
				item_id: itemId,
				call_id: tc.id,
				output_index: outputIndex,
				arguments: args,
			}),
		)
		controller.enqueue(
			sse({
				type: "response.output_item.done",
				output_index: outputIndex,
				item: {
					type: "function_call",
					id: itemId,
					name,
					arguments: args,
					call_id: tc.id,
				},
			}),
		)
		toolCalls.push(tc)
		outputIndex++
	}

	return new ReadableStream<string>({
		async start(controller) {
			controller.enqueue(
				sse({
					type: "response.created",
					response: {
						id: responseId,
						object: "response",
						status: "in_progress",
						model,
						output: [],
					},
				}),
			)
			controller.enqueue(
				sse({
					type: "response.output_item.added",
					output_index: 0,
					item: {
						type: "message",
						id: `msg_${responseId.slice(5)}`,
						role: "assistant",
						content: [],
					},
				}),
			)
			controller.enqueue(
				sse({
					type: "response.content_part.added",
					output_index: 0,
					content_index: 0,
					part: { type: "output_text", text: "" },
				}),
			)

			const reader = kiroResponse.body?.getReader()
			if (!reader) {
				controller.close()
				return
			}

			try {
				while (true) {
					const { done, value } = await reader.read()
					if (done) break

					const events = parser.feed(value)
					for (const event of events) {
						if (event.type === "content") {
							const text = event.data as string
							fullText += text
							controller.enqueue(
								sse({
									type: "response.output_text.delta",
									output_index: 0,
									content_index: 0,
									delta: text,
								}),
							)
						} else if (event.type === "tool_use") {
							logger.debug(`[Responses stream] tool_use: ${(event.data as ToolUseEvent).name} id=${(event.data as ToolUseEvent).id}`)
							emitToolCall(controller, event.data as ToolUseEvent)
						}
					}
				}
			} catch (err) {
				logger.error(`[Responses stream] ${err instanceof Error ? err.message : err}`, err instanceof Error ? err.stack : "")
			}

			// Flush any in-progress tool call that never got stop:true
			const flushed = parser.flush()
			for (const event of flushed) {
				if (event.type === "tool_use") {
					logger.debug(`[Responses stream] flushed tool_use: ${(event.data as ToolUseEvent).name} id=${(event.data as ToolUseEvent).id}`)
					emitToolCall(controller, event.data as ToolUseEvent)
				}
			}

			// Check for bracket-style tool calls in content
			const bracketTools = parseBracketToolCalls(fullText)
			if (bracketTools.length) {
				const seen = new Set(toolCalls.map((t) => t.id))
				for (const bt of bracketTools) {
					if (!seen.has(bt.id)) {
						emitToolCall(controller, bt)
					}
				}
			}

			closeTextPart(controller)

			// Build final output array
			const output: unknown[] = [
				{
					type: "message",
					role: "assistant",
					content: [{ type: "output_text", text: fullText }],
				},
			]
			for (const tc of toolCalls) {
				output.push({
					type: "function_call",
					id: `fc_${tc.id}`,
					name: unprefixToolName(tc.name),
					arguments: tc.arguments,
					call_id: tc.id,
				})
			}

			// tiktoken + 1.15x Claude correction
			const completionTokens = countTokens(fullText)
			const promptTokens = inputTokens
			const totalTokens = promptTokens + completionTokens
			logger.info(`Tokens: in=${promptTokens} out=${completionTokens} total=${totalTokens}`)

			controller.enqueue(
				sse({
					type: "response.completed",
					response: {
						id: responseId,
						object: "response",
						status: "completed",
						model,
						output,
						usage: {
							input_tokens: promptTokens,
							output_tokens: completionTokens,
							total_tokens: totalTokens,
						},
					},
				}),
			)
			controller.close()
			onDone?.({ prompt_tokens: promptTokens, completion_tokens: completionTokens, total_tokens: totalTokens })
		},
	})
}

export const responsesRoutes = new Elysia({ prefix: "/v1" })
	.derive(async ({ headers, request }) => {
		const authResult = await resolveAuth(
			headers.authorization ?? headers["x-api-key"],
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
