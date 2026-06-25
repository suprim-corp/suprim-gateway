import type { KiroAuthManager } from "../auth/manager"
import { normalizeModelName } from "../models/resolver"

export interface OpenAIMessage {
	role: "system" | "user" | "assistant" | "tool"
	content:
		| string
		| null
		| Array<{ type: string; text?: string; image_url?: { url: string } }>
	name?: string
	tool_calls?: Array<{
		id: string
		type: "function"
		function: { name: string; arguments: string }
	}>
	tool_call_id?: string
}

export interface OpenAITool {
	type?: "function"
	function?: {
		name: string
		description?: string
		parameters?: Record<string, unknown>
	}
	name?: string
	description?: string
	input_schema?: Record<string, unknown>
}

export interface ChatCompletionRequest {
	model: string
	messages: OpenAIMessage[]
	stream?: boolean
	temperature?: number
	top_p?: number
	max_tokens?: number
	max_completion_tokens?: number
	stop?: string | string[]
	tools?: OpenAITool[]
	tool_choice?: string | Record<string, unknown>
	reasoning_effort?: string
}

interface KiroToolSpec {
	toolSpecification: {
		name: string
		description: string
		inputSchema: Record<string, unknown>
	}
}

interface KiroHistoryMessage {
	userInputMessage?: {
		content: string | null
		userInputMessageContext: { editorState: { cursorState: null } }
	}
	assistantResponseMessage?: {
		content: string
		toolUses?: Array<{ toolUseId: string; name: string; input: unknown }>
	}
	toolResultMessage?: {
		toolUseId: string
		content: string
		status: string
	}
}

interface KiroPayload {
	conversationState: {
		currentMessage: {
			userInputMessage: {
				content: string
				userInputMessageContext: { editorState: { cursorState: null } }
				toolSpecs?: KiroToolSpec[]
			}
		}
		chatTriggerType: string
		customizationArn: string
		systemPrompt?: string
		history?: KiroHistoryMessage[]
	}
	profileArn: string
	source: string
	agentMode: string
	modelId: string
}

export function buildKiroPayload(
	req: ChatCompletionRequest,
	auth: KiroAuthManager,
): KiroPayload {
	const { system, messages } = convertMessages(req.messages)
	const modelId = normalizeModelName(req.model)

	const payload: KiroPayload = {
		conversationState: {
			currentMessage: {
				userInputMessage: {
					content:
						messages[messages.length - 1]?.userInputMessage
							?.content ?? "",
					userInputMessageContext: {
						editorState: { cursorState: null },
					},
				},
			},
			chatTriggerType: "MANUAL",
			customizationArn: "",
		},
		profileArn: auth.profileArn ?? "",
		source: "KIRO",
		agentMode: "VIBE",
		modelId,
	}

	if (system) {
		payload.conversationState.systemPrompt = system
	}

	if (messages.length > 1) {
		payload.conversationState.history = messages.slice(0, -1)
	}

	if (req.tools?.length) {
		payload.conversationState.currentMessage.userInputMessage.toolSpecs =
			convertTools(req.tools)
	}

	return payload
}

function convertMessages(messages: OpenAIMessage[]): {
	system: string | null
	messages: KiroHistoryMessage[]
} {
	let system: string | null = null
	const converted: KiroHistoryMessage[] = []

	for (const msg of messages) {
		if (msg.role === "system") {
			system = extractTextContent(msg.content)
			continue
		}

		if (msg.role === "user") {
			converted.push({
				userInputMessage: {
					content: extractTextContent(msg.content),
					userInputMessageContext: {
						editorState: { cursorState: null },
					},
				},
			})
		} else if (msg.role === "assistant") {
			const content = extractTextContent(msg.content)

			if (msg.tool_calls?.length) {
				converted.push({
					assistantResponseMessage: {
						content: content ?? "",
						toolUses: msg.tool_calls.map((tc) => ({
							toolUseId: tc.id,
							name: tc.function.name,
							input: safeJsonParse(tc.function.arguments),
						})),
					},
				})
			} else {
				converted.push({
					assistantResponseMessage: { content: content ?? "" },
				})
			}
		} else if (msg.role === "tool") {
			converted.push({
				toolResultMessage: {
					toolUseId: msg.tool_call_id ?? "",
					content: extractTextContent(msg.content) ?? "",
					status: "SUCCESS",
				},
			})
		}
	}

	return { system, messages: converted }
}

function convertTools(tools: OpenAITool[]): KiroToolSpec[] {
	return tools
		.map((tool): KiroToolSpec | null => {
			if (tool.type === "function" && tool.function) {
				return {
					toolSpecification: {
						name: tool.function.name,
						description: tool.function.description ?? "",
						inputSchema: tool.function.parameters ?? {
							type: "object",
							properties: {},
						},
					},
				}
			}
			if (tool.name) {
				return {
					toolSpecification: {
						name: tool.name,
						description: tool.description ?? "",
						inputSchema: tool.input_schema ?? {
							type: "object",
							properties: {},
						},
					},
				}
			}
			return null
		})
		.filter((t): t is KiroToolSpec => t !== null)
}

function extractTextContent(content: OpenAIMessage["content"]): string | null {
	if (content === null) return null
	if (typeof content === "string") return content
	if (Array.isArray(content)) {
		return content
			.filter((b) => b.type === "text" && b.text)
			.map((b) => b.text as string)
			.join("")
	}
	return null
}

function safeJsonParse(str: string): unknown {
	try {
		return JSON.parse(str)
	} catch {
		return {}
	}
}
