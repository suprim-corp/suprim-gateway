import type { ChatCompletionRequest, OpenAIMessage } from "../../converters/openai-to-kiro"

interface ResponsesInputMessage {
	type?: "message"
	role: "user" | "assistant" | "system" | "developer"
	content: string | Array<{ type: string; text?: string; image_url?: string; detail?: string }>
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

export type ResponsesInput =
	| ResponsesInputMessage
	| ResponsesInputFunctionCall
	| ResponsesInputFunctionCallOutput

export interface ResponsesRequest {
	model: string
	input: string | ResponsesInput[]
	stream?: boolean
	temperature?: number
	max_output_tokens?: number
	tools?: unknown[]
	tool_choice?: string
	reasoning?: { effort?: string }
}

function convertResponsesContent(
	blocks: Array<{ type: string; text?: string; image_url?: string; detail?: string }>,
): OpenAIMessage["content"] {
	const hasImage = blocks.some((b) => b.type === "input_image" && b.image_url)
	if (!hasImage) {
		return blocks
			.filter((c) => c.type === "input_text" || c.type === "text")
			.map((c) => c.text ?? "")
			.join("")
	}
	return blocks
		.map((b) => {
			if ((b.type === "input_text" || b.type === "text") && b.text) {
				return { type: "text" as const, text: b.text }
			}
			if (b.type === "input_image" && b.image_url) {
				return { type: "image_url" as const, image_url: { url: b.image_url } }
			}
			return null
		})
		.filter((b): b is NonNullable<typeof b> => b !== null)
}

export function inputToMessages(
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
					: convertResponsesContent(item.content)
			const role = item.role === "developer" ? "system" : item.role

			if (role === "assistant") {
				if (pendingAssistantContent) {
					messages.push({ role: "assistant", content: pendingAssistantContent })
				}
				pendingAssistantContent = typeof content === "string" ? content : ""
			} else {
				if (pendingAssistantContent) {
					messages.push({ role: "assistant", content: pendingAssistantContent })
					pendingAssistantContent = ""
				}
				messages.push({ role, content })
			}
		}
	}

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
