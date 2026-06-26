import { unprefixToolName } from "../../converters/openai-to-kiro"
import { AwsEventStreamParser, parseBracketToolCalls } from "../../kiro/parser"
import type { ToolUseEvent } from "../../kiro/types"
import { logger } from "../../logging/logger"
import { countTokens } from "../../utils/tokenizer"

export function createResponsesStream(
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

			const flushed = parser.flush()
			for (const event of flushed) {
				if (event.type === "tool_use") {
					logger.debug(`[Responses stream] flushed tool_use: ${(event.data as ToolUseEvent).name} id=${(event.data as ToolUseEvent).id}`)
					emitToolCall(controller, event.data as ToolUseEvent)
				}
			}

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
