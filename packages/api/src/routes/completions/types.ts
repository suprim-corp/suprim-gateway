export interface ChatCompletionChoice {
	index: number
	message: {
		role: "assistant"
		content: string | null
		tool_calls?: Array<{
			id: string
			type: "function"
			function: { name: string; arguments: string }
		}>
	}
	finish_reason: "stop" | "tool_calls"
}

export interface ChatCompletionResponse {
	id: string
	object: "chat.completion"
	created: number
	model: string
	choices: ChatCompletionChoice[]
	usage: {
		prompt_tokens: number
		completion_tokens: number
		total_tokens: number
	}
}
