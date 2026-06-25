export interface KiroEvent {
	type:
		| "content"
		| "thinking"
		| "tool_use"
		| "usage"
		| "context_usage"
		| "error"
	content?: string
	thinkingContent?: string
	toolUse?: ToolUseEvent
	usage?: number
	contextUsagePercentage?: number
	isFirstThinkingChunk?: boolean
	isLastThinkingChunk?: boolean
}

export interface ToolUseEvent {
	id: string
	name: string
	arguments: string
}

export interface RawKiroEvent {
	type: string
	data: string | number | ToolUseEvent
}
