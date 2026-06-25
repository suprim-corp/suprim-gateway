import type { RawKiroEvent, ToolUseEvent } from "./types"
import { logger } from "../logging/logger"

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
	// Top-level tool use fields (Kiro sends these directly)
	name?: string
	input?: string
	stop?: boolean
	toolUseId?: string
}

interface KiroToolEvent {
	name?: string
	input?: string
	stop?: boolean
	toolUseId?: string
}

export class AwsEventStreamParser {
	private buffer = ""
	private binaryBuffer: Uint8Array = new Uint8Array(0)
	private currentToolCall: Partial<ToolUseEvent> | null = null
	private toolCallArgs = ""
	private toolCalls: ToolUseEvent[] = []
	private toolCallCounter = 0
	private isBinaryMode: boolean | null = null

	feed(chunk: Uint8Array | string): RawKiroEvent[] {
		if (typeof chunk === "string") {
			if (this.isBinaryMode === null) this.isBinaryMode = false
			this.buffer += chunk
			return this.parseTextBuffer()
		}

		// Detect mode on first binary chunk
		if (this.isBinaryMode === null) {
			// AWS binary event stream starts with a 4-byte big-endian length
			// If first bytes look like valid JSON start ({, [, or whitespace before {), treat as text
			const firstByte = chunk[0]
			if (firstByte === 0x7b || firstByte === 0x5b || firstByte === 0x0a || firstByte === 0x0d || firstByte === 0x20) {
				this.isBinaryMode = false
			} else {
				this.isBinaryMode = true
			}
		}

		if (!this.isBinaryMode) {
			this.buffer += new TextDecoder().decode(chunk)
			return this.parseTextBuffer()
		}

		return this.feedBinary(chunk)
	}

	private feedBinary(chunk: Uint8Array): RawKiroEvent[] {
		// Append to binary buffer
		const newBuf = new Uint8Array(this.binaryBuffer.length + chunk.length)
		newBuf.set(this.binaryBuffer)
		newBuf.set(chunk, this.binaryBuffer.length)
		this.binaryBuffer = newBuf

		const events: RawKiroEvent[] = []

		while (this.binaryBuffer.length >= 12) {
			const view = new DataView(this.binaryBuffer.buffer, this.binaryBuffer.byteOffset, this.binaryBuffer.byteLength)
			const totalLength = view.getUint32(0)

			if (totalLength < 16 || totalLength > 16 * 1024 * 1024) {
				// Invalid frame — might be text after all, switch modes
				this.isBinaryMode = false
				this.buffer += new TextDecoder().decode(this.binaryBuffer)
				this.binaryBuffer = new Uint8Array(0)
				return [...events, ...this.parseTextBuffer()]
			}

			if (this.binaryBuffer.length < totalLength) break

			const headersLength = view.getUint32(4)
			// prelude CRC at bytes 8-11
			const headersEnd = 12 + headersLength
			const payloadEnd = totalLength - 4 // last 4 bytes = message CRC

			if (payloadEnd > headersEnd) {
				const payload = this.binaryBuffer.slice(headersEnd, payloadEnd)
				const text = new TextDecoder().decode(payload)

				// Try to parse JSON from payload
				const trimmed = text.trim()
				if (trimmed.startsWith("{")) {
					try {
						const obj = JSON.parse(trimmed) as KiroEventObject
						const parsed = this.processObject(obj)
						if (parsed) events.push(...parsed)
					} catch {
						// payload might contain multiple JSON objects or partial data
						// fall back to text scanning
						this.buffer += text
						const textEvents = this.parseTextBuffer()
						events.push(...textEvents)
					}
				}
			}

			this.binaryBuffer = this.binaryBuffer.slice(totalLength)
		}

		return events
	}

	private parseTextBuffer(): RawKiroEvent[] {
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
				logger.warn(`[Parser] malformed JSON (${jsonStr.length} chars): ${jsonStr.slice(0, 200)}`)
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

		// Top-level tool use events (name/input/stop/toolUseId at root)
		if (obj.toolUseId != null && (obj.name != null || obj.input != null || obj.stop != null)) {
			return this.processToolEvent({
				name: obj.name,
				input: obj.input,
				stop: obj.stop,
				toolUseId: obj.toolUseId,
			})
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

		// New tool call starting — flush any in-progress one first
		// Kiro sends name on every chunk, so only treat as new if toolUseId differs
		if (event.name && event.toolUseId) {
			if (this.currentToolCall && this.currentToolCall.id === event.toolUseId) {
				// Same tool, just accumulate input
				if (event.input != null) {
					this.toolCallArgs += event.input
				}
				return null
			}

			const events: RawKiroEvent[] = []
			if (this.currentToolCall) {
				const toolCall: ToolUseEvent = {
					id: this.currentToolCall.id ?? "",
					name: this.currentToolCall.name ?? "",
					arguments: this.toolCallArgs,
				}
				this.toolCalls.push(toolCall)
				events.push({ type: "tool_use", data: toolCall })
				this.toolCallArgs = ""
			}
			this.currentToolCall = {
				id: event.toolUseId,
				name: event.name,
			}
			this.toolCallArgs = event.input ?? ""
			return events.length ? events : null
		}

		if (event.input != null && this.currentToolCall) {
			this.toolCallArgs += event.input
			return null
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

	flush(): RawKiroEvent[] {
		if (this.currentToolCall) {
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
		return []
	}

	reset() {
		this.buffer = ""
		this.binaryBuffer = new Uint8Array(0)
		this.isBinaryMode = null
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
