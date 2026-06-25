import type { RawKiroEvent, ToolUseEvent } from "./types"

interface KiroEventObject {
	assistantResponseEvent?: {
		messageMetadataEvent?: {
			usage?: { creditUsage?: number }
			contextUsagePercentage?: number
		}
		assistantResponseEvent?: {
			content?: string | Array<{ text?: string }>
		}
		content?: string
		toolUseEvent?: KiroToolEvent
	}
	toolUseEvent?: KiroToolEvent
	supplementaryWebChatEvent?: { content?: string }
	codeEvent?: { content?: string }
	content?: string
	modelId?: string
	contextUsagePercentage?: number
}

interface KiroToolEvent {
	name?: string
	input?: string
	stop?: boolean
}

export class AwsEventStreamParser {
	private buffer = ""
	private currentToolCall: Partial<ToolUseEvent> | null = null
	private toolCallArgs = ""
	private toolCalls: ToolUseEvent[] = []
	private toolCallCounter = 0

	feed(chunk: Uint8Array | string): RawKiroEvent[] {
		const text =
			typeof chunk === "string" ? chunk : new TextDecoder().decode(chunk)
		this.buffer += text
		return this.parseBuffer()
	}

	private parseBuffer(): RawKiroEvent[] {
		const events: RawKiroEvent[] = []
		let searchFrom = 0

		while (true) {
			const start = this.buffer.indexOf("{", searchFrom)
			if (start === -1) break

			const end = this.findMatchingBrace(this.buffer, start)
			if (end === -1) break

			const jsonStr = this.buffer.slice(start, end + 1)
			searchFrom = end + 1

			try {
				const obj = JSON.parse(jsonStr) as KiroEventObject
				const parsed = this.processObject(obj)
				if (parsed) events.push(...parsed)
			} catch {
				// malformed JSON, skip
			}
		}

		if (searchFrom > 0) {
			this.buffer = this.buffer.slice(searchFrom)
		}

		return events
	}

	private processObject(obj: KiroEventObject): RawKiroEvent[] | null {
		const events: RawKiroEvent[] = []

		// Top-level content field (e.g. {"content":"Hi","modelId":"claude-sonnet-4.6"})
		if (obj.content && obj.modelId) {
			events.push({ type: "content", data: obj.content })
			return events
		}

		// Top-level contextUsagePercentage
		if (obj.contextUsagePercentage != null && !obj.assistantResponseEvent) {
			events.push({ type: "context_usage", data: obj.contextUsagePercentage })
			return events
		}

		if (obj.assistantResponseEvent?.messageMetadataEvent) {
			const meta = obj.assistantResponseEvent.messageMetadataEvent
			if (meta.usage?.creditUsage != null) {
				events.push({ type: "usage", data: meta.usage.creditUsage })
			}
			if (meta.contextUsagePercentage != null) {
				events.push({
					type: "context_usage",
					data: meta.contextUsagePercentage,
				})
			}
			return events.length ? events : null
		}

		if (obj.assistantResponseEvent?.assistantResponseEvent?.content) {
			const content =
				obj.assistantResponseEvent.assistantResponseEvent.content

			if (typeof content === "string") {
				events.push({ type: "content", data: content })
				return events
			}

			if (Array.isArray(content)) {
				for (const block of content) {
					if (block.text) {
						events.push({ type: "content", data: block.text })
					}
				}
				return events.length ? events : null
			}

			return null
		}

		if (obj.assistantResponseEvent?.content) {
			const content = obj.assistantResponseEvent.content
			if (typeof content === "string") {
				events.push({ type: "content", data: content })
				return events
			}
		}

		if (obj.toolUseEvent || obj.assistantResponseEvent?.toolUseEvent) {
			const toolEvent =
				obj.toolUseEvent ?? obj.assistantResponseEvent?.toolUseEvent
			return this.processToolEvent(toolEvent)
		}

		if (obj.supplementaryWebChatEvent?.content) {
			events.push({
				type: "content",
				data: obj.supplementaryWebChatEvent.content,
			})
			return events
		}

		if (obj.codeEvent?.content) {
			events.push({ type: "content", data: obj.codeEvent.content })
			return events
		}

		return null
	}

	private processToolEvent(
		event: KiroToolEvent | undefined,
	): RawKiroEvent[] | null {
		if (!event) return null

		if (event.name) {
			this.currentToolCall = {
				id: `call_${(++this.toolCallCounter).toString(16).padStart(8, "0")}`,
				name: event.name,
			}
			this.toolCallArgs = event.input ?? ""
			return null
		}

		if (event.input != null && this.currentToolCall) {
			this.toolCallArgs += event.input
			return null
		}

		if (event.stop && this.currentToolCall) {
			const id = this.currentToolCall.id ?? ""
			const name = this.currentToolCall.name ?? ""
			const toolCall: ToolUseEvent = {
				id,
				name,
				arguments: this.toolCallArgs,
			}
			this.toolCalls.push(toolCall)
			this.currentToolCall = null
			this.toolCallArgs = ""
			return [{ type: "tool_use", data: toolCall }]
		}

		return null
	}

	private findMatchingBrace(str: string, start: number): number {
		let depth = 0
		let inString = false
		let escaped = false

		for (let i = start; i < str.length; i++) {
			const ch = str[i]

			if (escaped) {
				escaped = false
				continue
			}

			if (ch === "\\") {
				escaped = true
				continue
			}

			if (ch === '"') {
				inString = !inString
				continue
			}

			if (inString) continue

			if (ch === "{") depth++
			else if (ch === "}") {
				depth--
				if (depth === 0) return i
			}
		}

		return -1
	}

	getToolCalls(): ToolUseEvent[] {
		return this.toolCalls
	}

	reset() {
		this.buffer = ""
		this.currentToolCall = null
		this.toolCallArgs = ""
		this.toolCalls = []
		this.toolCallCounter = 0
	}
}

export function parseBracketToolCalls(text: string): ToolUseEvent[] {
	const results: ToolUseEvent[] = []
	const regex = /\[Called (\w+) with args: (\{[\s\S]*?\})\]/g
	let counter = 0
	let match = regex.exec(text)

	while (match !== null) {
		try {
			JSON.parse(match[2])
			results.push({
				id: `call_bracket_${(++counter).toString(16).padStart(4, "0")}`,
				name: match[1],
				arguments: match[2],
			})
		} catch {
			// invalid JSON in brackets, skip
		}
		match = regex.exec(text)
	}

	return results
}
