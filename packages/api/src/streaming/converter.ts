import { AwsEventStreamParser, parseBracketToolCalls } from "../kiro/parser"
import { unprefixToolName } from "../converters/openai-to-kiro"
import type { ToolUseEvent } from "../kiro/types"
import { generateCompletionId } from "../utils/ids"

export interface StreamChunk {
	id: string
	object: "chat.completion.chunk"
	created: number
	model: string
	choices: Array<{
		index: number
		delta: {
			role?: string
			content?: string | null
			tool_calls?: Array<{
				index: number
				id?: string
				type?: string
				function?: { name?: string; arguments?: string }
			}>
		}
		finish_reason: string | null
	}>
	usage?: {
		prompt_tokens: number
		completion_tokens: number
		total_tokens: number
	}
}

/**
 * Stream Kiro response → OpenAI SSE format.
 * Returns a ReadableStream of `data: {...}\n\n` strings.
 */
export function createOpenAIStream(
	response: Response,
	model: string,
	onDone?: (usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number }) => void,
): ReadableStream<string> {
	const completionId = generateCompletionId()
	const created = Math.floor(Date.now() / 1000)
	const parser = new AwsEventStreamParser()
	let fullContent = ""
	let toolCalls: ToolUseEvent[] = []
	let sentRole = false
	let contextUsage: number | null = null
	let _creditUsage: number | null = null

	const _encoder = new TextEncoder()

	return new ReadableStream<string>({
		async start(controller) {
			try {
				const reader = response.body?.getReader()
				if (!reader) {
					controller.enqueue(
						formatSSE(
							makeErrorChunk(
								completionId,
								created,
								model,
								"No response body",
							),
						),
					)
					controller.enqueue("data: [DONE]\n\n")
					controller.close()
					return
				}

				while (true) {
					const { done, value } = await reader.read()
					if (done) break

					const events = parser.feed(value)
					for (const event of events) {
						if (event.type === "content") {
							const text = event.data as string
							fullContent += text

							// Send role on first content
							if (!sentRole) {
								sentRole = true
								controller.enqueue(
									formatSSE(
										makeChunk(
											completionId,
											created,
											model,
											{ role: "assistant" },
										),
									),
								)
							}

							controller.enqueue(
								formatSSE(
									makeChunk(completionId, created, model, {
										content: text,
									}),
								),
							)
						} else if (event.type === "tool_use") {
							const tool = event.data as ToolUseEvent
							toolCalls.push(tool)
						} else if (event.type === "usage") {
							_creditUsage = event.data as number
						} else if (event.type === "context_usage") {
							contextUsage = event.data as number
						}
					}
				}

				// Check for bracket-style tool calls in content
				const bracketTools = parseBracketToolCalls(fullContent)
				if (bracketTools.length) {
					toolCalls = deduplicateToolCalls([
						...toolCalls,
						...bracketTools,
					])
				}

				// Emit tool calls
				if (toolCalls.length) {
					if (!sentRole) {
						sentRole = true
						controller.enqueue(
							formatSSE(
								makeChunk(completionId, created, model, {
									role: "assistant",
								}),
							),
						)
					}
					for (let i = 0; i < toolCalls.length; i++) {
						const tc = toolCalls[i]
						controller.enqueue(
							formatSSE(
								makeChunk(completionId, created, model, {
									tool_calls: [
										{
											index: i,
											id: tc.id,
											type: "function",
											function: {
												name: unprefixToolName(tc.name),
												arguments: tc.arguments,
											},
										},
									],
								}),
							),
						)
					}
				}

				// Final chunk with finish_reason + usage
				const finishReason = toolCalls.length ? "tool_calls" : "stop"
				const completionTokens = estimateTokens(fullContent)
				const promptTokens =
					contextUsage != null
						? Math.max(
								0,
								Math.floor((contextUsage / 100) * 200000) -
									completionTokens,
							)
						: 0

				const finalChunk: StreamChunk = {
					id: completionId,
					object: "chat.completion.chunk",
					created,
					model,
					choices: [
						{ index: 0, delta: {}, finish_reason: finishReason },
					],
					usage: {
						prompt_tokens: promptTokens,
						completion_tokens: completionTokens,
						total_tokens: promptTokens + completionTokens,
					},
				}
				controller.enqueue(formatSSE(finalChunk))
				controller.enqueue("data: [DONE]\n\n")
				controller.close()
				onDone?.({ prompt_tokens: promptTokens, completion_tokens: completionTokens, total_tokens: promptTokens + completionTokens })
			} catch (err: unknown) {
				const message = err instanceof Error ? err.message : String(err)
				controller.enqueue(
					formatSSE(
						makeErrorChunk(completionId, created, model, message),
					),
				)
				controller.enqueue("data: [DONE]\n\n")
				controller.close()
			}
		},
	})
}

/**
 * Collect full response (non-streaming mode).
 */
export async function collectResponse(
	response: Response,
	_model: string,
): Promise<{
	content: string
	toolCalls: ToolUseEvent[]
	usage: {
		prompt_tokens: number
		completion_tokens: number
		total_tokens: number
	}
}> {
	const parser = new AwsEventStreamParser()
	let fullContent = ""
	let contextUsage: number | null = null
	let toolCalls: ToolUseEvent[] = []

	const reader = response.body?.getReader()
	if (!reader) throw new Error("No response body")

	while (true) {
		const { done, value } = await reader.read()
		if (done) break

		const events = parser.feed(value)
		for (const event of events) {
			if (event.type === "content") fullContent += event.data as string
			else if (event.type === "tool_use") toolCalls.push(event.data as ToolUseEvent)
			else if (event.type === "context_usage") contextUsage = event.data as number
		}
	}

	const bracketTools = parseBracketToolCalls(fullContent)
	if (bracketTools.length) {
		toolCalls = deduplicateToolCalls([...toolCalls, ...bracketTools])
	}

	const completionTokens = estimateTokens(fullContent)
	const promptTokens =
		contextUsage != null
			? Math.max(
					0,
					Math.floor((contextUsage / 100) * 200000) -
						completionTokens,
				)
			: 0

	return {
		content: fullContent,
		toolCalls,
		usage: {
			prompt_tokens: promptTokens,
			completion_tokens: completionTokens,
			total_tokens: promptTokens + completionTokens,
		},
	}
}

function formatSSE(data: StreamChunk): string {
	return `data: ${JSON.stringify(data)}\n\n`
}

function makeChunk(
	id: string,
	created: number,
	model: string,
	delta: StreamChunk["choices"][0]["delta"],
): StreamChunk {
	return {
		id,
		object: "chat.completion.chunk",
		created,
		model,
		choices: [{ index: 0, delta, finish_reason: null }],
	}
}

function makeErrorChunk(
	id: string,
	created: number,
	model: string,
	message: string,
): StreamChunk {
	return {
		id,
		object: "chat.completion.chunk",
		created,
		model,
		choices: [
			{
				index: 0,
				delta: { content: `[Error: ${message}]` },
				finish_reason: "stop",
			},
		],
	}
}

function deduplicateToolCalls(calls: ToolUseEvent[]): ToolUseEvent[] {
	const seen = new Map<string, ToolUseEvent>()
	for (const tc of calls) {
		const existing = seen.get(tc.id)
		if (!existing || (tc.arguments && !existing.arguments)) {
			seen.set(tc.id, tc)
		}
	}
	return [...seen.values()]
}

// ponytail: rough estimate ~4 chars/token, good enough for MVP
function estimateTokens(text: string): number {
	return Math.ceil(text.length / 4)
}
