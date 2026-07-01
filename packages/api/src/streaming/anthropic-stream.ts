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

	let closed = false

	return new ReadableStream<string>({
		async start(controller) {
			const safe = {
				enqueue(chunk: string) {
					if (closed) return
					try { controller.enqueue(chunk) } catch (e) { closed = true; logger.warn(`[Anthropic stream] enqueue failed: ${e instanceof Error ? e.message : e}`) }
				},
				close() {
					if (closed) return
					closed = true
					try { controller.close() } catch (e) { logger.warn(`[Anthropic stream] close failed: ${e instanceof Error ? e.message : e}`) }
				},
			}

			safe.enqueue(
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
				safe.enqueue(sse("message_stop", { type: "message_stop" }))
				safe.close()
				return
			}

			try {
				while (true) {
					const { done, value } = await reader.read()
					if (done) break

					const events = parser.feed(value)
					for (const event of events) {
						if (closed) break
						if (event.type === "content") {
							const text = event.data as string
							fullText += text
							if (!textBlockOpen) {
								textBlockOpen = true
								safe.enqueue(
									sse("content_block_start", {
										type: "content_block_start",
										index: blockIndex,
										content_block: { type: "text", text: "" },
									}),
								)
							}
							safe.enqueue(
								sse("content_block_delta", {
									type: "content_block_delta",
									index: blockIndex,
									delta: { type: "text_delta", text },
								}),
							)
						} else if (event.type === "tool_use") {
							const tc = event.data as ToolUseEvent
							logger.debug(`[Anthropic stream] tool_use: ${tc.name} id=${tc.id}`)
							toolCalls.push(tc)
							// close text block
							if (textBlockOpen) {
								textBlockOpen = false
								safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
								blockIndex++
							}
							const name = unprefixToolName(tc.name)
							safe.enqueue(sse("content_block_start", { type: "content_block_start", index: blockIndex, content_block: { type: "tool_use", id: tc.id, name, input: {} } }))
							const args = tc.arguments
							for (let ci = 0; ci < args.length; ci += 16_384) {
								safe.enqueue(sse("content_block_delta", { type: "content_block_delta", index: blockIndex, delta: { type: "input_json_delta", partial_json: args.slice(ci, ci + 16_384) } }))
							}
							safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
							blockIndex++
						}
					}
				}
			} catch (err) {
				logger.error(`[Anthropic stream] ${err instanceof Error ? err.message : err}`)
			}

			if (closed) return

			const flushed = parser.flush()
			for (const event of flushed) {
				if (event.type === "tool_use") {
					const tc = event.data as ToolUseEvent
					toolCalls.push(tc)
					if (textBlockOpen) {
						textBlockOpen = false
						safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
						blockIndex++
					}
					const name = unprefixToolName(tc.name)
					safe.enqueue(sse("content_block_start", { type: "content_block_start", index: blockIndex, content_block: { type: "tool_use", id: tc.id, name, input: {} } }))
					const args = tc.arguments
					for (let ci = 0; ci < args.length; ci += 16_384) {
						safe.enqueue(sse("content_block_delta", { type: "content_block_delta", index: blockIndex, delta: { type: "input_json_delta", partial_json: args.slice(ci, ci + 16_384) } }))
					}
					safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
					blockIndex++
				}
			}

			const bracketTools = parseBracketToolCalls(fullText)
			if (bracketTools.length) {
				const seen = new Set(toolCalls.map((t) => t.id))
				for (const bt of bracketTools) {
					if (!seen.has(bt.id)) {
						toolCalls.push(bt)
						if (textBlockOpen) {
							textBlockOpen = false
							safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
							blockIndex++
						}
						const name = unprefixToolName(bt.name)
						safe.enqueue(sse("content_block_start", { type: "content_block_start", index: blockIndex, content_block: { type: "tool_use", id: bt.id, name, input: {} } }))
						const args = bt.arguments
						for (let ci = 0; ci < args.length; ci += 16_384) {
							safe.enqueue(sse("content_block_delta", { type: "content_block_delta", index: blockIndex, delta: { type: "input_json_delta", partial_json: args.slice(ci, ci + 16_384) } }))
						}
						safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
						blockIndex++
					}
				}
			}

			if (textBlockOpen) {
				textBlockOpen = false
				safe.enqueue(sse("content_block_stop", { type: "content_block_stop", index: blockIndex }))
				blockIndex++
			}

			const outputTokens = countTokens(fullText)
			const stopReason = toolCalls.length ? "tool_use" : "end_turn"

			safe.enqueue(
				sse("message_delta", {
					type: "message_delta",
					delta: { stop_reason: stopReason, stop_sequence: null },
					usage: { output_tokens: outputTokens },
				}),
			)
			safe.enqueue(sse("message_stop", { type: "message_stop" }))
			safe.close()
			onDone?.({ input_tokens: inputTokens, output_tokens: outputTokens })
		},
		cancel() {
			closed = true
		},
	})
}
