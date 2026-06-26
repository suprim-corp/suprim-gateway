import { AwsEventStreamParser, parseBracketToolCalls } from "../kiro/parser"
import { unprefixToolName } from "../converters/openai-to-kiro"
import type { ToolUseEvent } from "../kiro/types"
import { logger } from "../logging/logger"
import { countTokens } from "../utils/tokenizer"

export function createAnthropicStream(
	kiroResponse: Response,
	model: string,
	responseId: string,
	inputTokens: number,
	onDone?: (usage: { input_tokens: number; output_tokens: number }) => void,
): ReadableStream<string> {
	const parser = new AwsEventStreamParser()
	let fullText = ""
	const toolCalls: ToolUseEvent[] = []
	let blockIndex = 0
	let textBlockOpen = false

	function sse(event: string, data: unknown): string {
		return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
	}

	function openTextBlock(controller: ReadableStreamDefaultController<string>) {
		if (textBlockOpen) return
		textBlockOpen = true
		controller.enqueue(
			sse("content_block_start", {
				type: "content_block_start",
				index: blockIndex,
				content_block: { type: "text", text: "" },
			}),
		)
	}

	function closeTextBlock(controller: ReadableStreamDefaultController<string>) {
		if (!textBlockOpen) return
		textBlockOpen = false
		controller.enqueue(
			sse("content_block_stop", { type: "content_block_stop", index: blockIndex }),
		)
		blockIndex++
	}

	function emitToolCall(
		controller: ReadableStreamDefaultController<string>,
		tc: ToolUseEvent,
	) {
		closeTextBlock(controller)
		const name = unprefixToolName(tc.name)
		controller.enqueue(
			sse("content_block_start", {
				type: "content_block_start",
				index: blockIndex,
				content_block: { type: "tool_use", id: tc.id, name, input: {} },
			}),
		)
		const CHUNK_SIZE = 16_384
		const args = tc.arguments
		for (let i = 0; i < args.length; i += CHUNK_SIZE) {
			controller.enqueue(
				sse("content_block_delta", {
					type: "content_block_delta",
					index: blockIndex,
					delta: { type: "input_json_delta", partial_json: args.slice(i, i + CHUNK_SIZE) },
				}),
			)
		}
		controller.enqueue(
			sse("content_block_stop", { type: "content_block_stop", index: blockIndex }),
		)
		blockIndex++
	}

	return new ReadableStream<string>({
		async start(controller) {
			controller.enqueue(
				sse("message_start", {
					type: "message_start",
					message: {
						id: responseId,
						type: "message",
						role: "assistant",
						content: [],
						model,
						stop_reason: null,
						stop_sequence: null,
						usage: { input_tokens: inputTokens, output_tokens: 0 },
					},
				}),
			)

			const reader = kiroResponse.body?.getReader()
			if (!reader) {
				controller.enqueue(sse("message_stop", { type: "message_stop" }))
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
							openTextBlock(controller)
							controller.enqueue(
								sse("content_block_delta", {
									type: "content_block_delta",
									index: blockIndex,
									delta: { type: "text_delta", text },
								}),
							)
						} else if (event.type === "tool_use") {
							logger.debug(`[Anthropic stream] tool_use: ${(event.data as ToolUseEvent).name} id=${(event.data as ToolUseEvent).id}`)
							emitToolCall(controller, event.data as ToolUseEvent)
						}
					}
				}
			} catch (err) {
				logger.error(`[Anthropic stream] ${err instanceof Error ? err.message : err}`)
			}

			const flushed = parser.flush()
			for (const event of flushed) {
				if (event.type === "tool_use") {
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

			closeTextBlock(controller)

			const outputTokens = countTokens(fullText)
			const stopReason = toolCalls.length ? "tool_use" : "end_turn"

			controller.enqueue(
				sse("message_delta", {
					type: "message_delta",
					delta: { stop_reason: stopReason, stop_sequence: null },
					usage: { output_tokens: outputTokens },
				}),
			)
			controller.enqueue(sse("message_stop", { type: "message_stop" }))
			controller.close()
			onDone?.({ input_tokens: inputTokens, output_tokens: outputTokens })
		},
	})
}
