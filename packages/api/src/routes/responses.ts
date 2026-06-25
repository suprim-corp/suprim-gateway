import { Elysia } from "elysia"
import { env } from "../config"
import {
	buildKiroPayload,
	type ChatCompletionRequest,
} from "../converters/openai-to-kiro"
import { AwsEventStreamParser } from "../kiro/parser"
import { logRequest } from "../logging"
import { collectResponse } from "../streaming/converter"
import {
	type AuthResult,
	checkModelAccess,
	checkRateLimit,
	recordUsage,
	resolveAuth,
} from "../virtual-keys"
import { getAuth, getClient, modelResolver } from "./openai"

interface ResponsesInput {
	type?: "message"
	role: "user" | "assistant" | "system" | "developer"
	content: string | Array<{ type: string; text?: string }>
}

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
	return input.map((msg) => {
		const content =
			typeof msg.content === "string"
				? msg.content
				: msg.content
						.filter(
							(c) => c.type === "input_text" || c.type === "text",
						)
						.map((c) => c.text)
						.join("")
		const role = msg.role === "developer" ? "system" : msg.role
		return { role, content }
	})
}

function createResponsesStream(
	kiroResponse: Response,
	model: string,
	responseId: string,
): ReadableStream<string> {
	const parser = new AwsEventStreamParser()
	let fullText = ""

	function sse(event: { type: string; [k: string]: unknown }): string {
		return `data: ${JSON.stringify(event)}\n\n`
	}

	return new ReadableStream<string>({
		async start(controller) {
			// Emit initial Responses API events
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

			// Read Kiro binary stream directly
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
						}
					}
				}
			} catch (err) {
				// Stream error — still close gracefully
			}

			// Emit completion events
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
			controller.enqueue(
				sse({
					type: "response.completed",
					response: {
						id: responseId,
						object: "response",
						status: "completed",
						model,
						output: [
							{
								type: "message",
								role: "assistant",
								content: [
									{ type: "output_text", text: fullText },
								],
							},
						],
						usage: {
							input_tokens: 0,
							output_tokens: 0,
							total_tokens: 0,
						},
					},
				}),
			)
			controller.close()
		},
	})
}

export const responsesRoutes = new Elysia({ prefix: "/v1" })
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
	.post("/responses", async ({ body, set, authResult }) => {
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
			const stream = createResponsesStream(
				response,
				resolvedModel,
				responseId,
			)
			set.headers["content-type"] = "text/event-stream"
			set.headers["cache-control"] = "no-cache"
			set.headers.connection = "keep-alive"
			if (auth.type === "virtual_key" && auth.key) {
				recordUsage(auth.key.id, 0)
			}
			logRequest({
				virtualKeyId: keyId,
				model: resolvedModel,
				requestedModel: req.model,
				status: 200,
				streaming: true,
				latencyMs: Date.now() - startTime,
			})
			return new Response(stream as unknown as BodyInit)
		}

		const result = await collectResponse(response, resolvedModel)

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
				input_tokens: result.usage.prompt_tokens,
				output_tokens: result.usage.completion_tokens,
				total_tokens: result.usage.total_tokens,
			},
		}
	})
