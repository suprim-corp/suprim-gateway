import type { OpenAIMessage, OpenAITool } from "../../converters/openai-to-kiro"

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

export interface AnthropicRequest {
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

export function convertMessages(messages: AnthropicMessage[]): OpenAIMessage[] {
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

export function convertTools(tools: AnthropicTool[]): OpenAITool[] {
	return tools.map((t) => ({
		type: "function" as const,
		function: {
			name: t.name,
			description: t.description ?? "",
			parameters: t.input_schema,
		},
	}))
}
