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
		inputSchema: { json: Record<string, unknown> }
	}
}

interface KiroUserInputMessage {
	content: string
	modelId: string
	origin: string
	userInputMessageContext?: {
		tools?: KiroToolSpec[]
		toolResults?: Array<{
			content: Array<{ text: string }>
			status: string
			toolUseId: string
		}>
	}
}

interface KiroHistoryUserMessage {
	content: string
	modelId: string
	origin: string
	userInputMessageContext?: {
		toolResults?: Array<{
			content: Array<{ text: string }>
			status: string
			toolUseId: string
		}>
	}
}

interface KiroHistoryEntry {
	userInputMessage?: KiroHistoryUserMessage
	assistantResponseMessage?: {
		content: string
		toolUses?: Array<{ toolUseId: string; name: string; input: unknown }>
	}
}

interface KiroPayload {
	conversationState: {
		chatTriggerType: string
		conversationId: string
		currentMessage: {
			userInputMessage: KiroUserInputMessage
		}
		history?: KiroHistoryEntry[]
	}
	profileArn?: string
}

export function buildKiroPayload(
	req: ChatCompletionRequest,
	auth: KiroAuthManager,
): KiroPayload {
	const { system, messages } = convertMessages(req.messages)
	const modelId = normalizeModelName(req.model)
	const conversationId = crypto.randomUUID()

	// Current message content (last user message)
	let currentContent =
		messages[messages.length - 1]?.userInputMessage?.content ?? ""

	// If system prompt and no history, prepend to current message
	if (system && messages.length <= 1) {
		currentContent = `${system}\n\n${currentContent}`
	}

	if (!currentContent) {
		currentContent = "(empty placeholder)"
	}

	const userInputMessage: KiroUserInputMessage = {
		content: currentContent,
		modelId,
		origin: "AI_EDITOR",
	}

	// Tools go in userInputMessageContext
	if (req.tools?.length) {
		const tools = convertTools(req.tools)
		if (!userInputMessage.userInputMessageContext) {
			userInputMessage.userInputMessageContext = {}
		}
		userInputMessage.userInputMessageContext.tools = tools
	}

	const payload: KiroPayload = {
		conversationState: {
			chatTriggerType: "MANUAL",
			conversationId,
			currentMessage: {
				userInputMessage,
			},
		},
	}

	// Build history if there are prior messages
	if (messages.length > 1) {
		const history = messages.slice(0, -1)
		// If system prompt exists, prepend to first user message in history
		if (system && history.length > 0 && history[0].userInputMessage) {
			history[0].userInputMessage.content = `${system}\n\n${history[0].userInputMessage.content}`
		}
		// Set modelId on all history user messages
		for (const entry of history) {
			if (entry.userInputMessage) {
				entry.userInputMessage.modelId = modelId
			}
		}
		payload.conversationState.history = history
	}

	if (auth.profileArn) {
		payload.profileArn = auth.profileArn
	}

	return payload
}

function convertMessages(messages: OpenAIMessage[]): {
	system: string | null
	messages: KiroHistoryEntry[]
} {
	let system: string | null = null
	const converted: KiroHistoryEntry[] = []

	// Normalize: extract system, convert roles, merge adjacent same-role
	const normalized: Array<{ role: "user" | "assistant"; content: string; tool_calls?: OpenAIMessage["tool_calls"]; tool_call_id?: string }> = []

	for (const msg of messages) {
		if (msg.role === "system") {
			const text = extractTextContent(msg.content)
			system = system ? `${system}\n\n${text}` : text
			continue
		}

		const role: "user" | "assistant" = msg.role === "tool" ? "user" : msg.role
		const content = extractTextContent(msg.content)

		normalized.push({
			role,
			content: content ?? "",
			tool_calls: msg.tool_calls,
			tool_call_id: msg.tool_call_id,
		})
	}

	// Ensure first message is user
	if (normalized.length > 0 && normalized[0].role !== "user") {
		normalized.unshift({ role: "user", content: "(empty placeholder)" })
	}

	// Ensure alternating roles by inserting placeholders
	const alternating: typeof normalized = []
	for (const msg of normalized) {
		if (alternating.length > 0) {
			const last = alternating[alternating.length - 1]
			if (msg.role === last.role) {
				const filler = msg.role === "user" ? "assistant" : "user"
				alternating.push({ role: filler, content: "(empty placeholder)" })
			}
		}
		alternating.push(msg)
	}

	// Convert to Kiro history format
	const modelId = "placeholder" // will be overwritten; history uses same modelId pattern
	for (const msg of alternating) {
		if (msg.role === "user") {
			const entry: KiroHistoryEntry = {
				userInputMessage: {
					content: msg.content || "(empty placeholder)",
					modelId: "", // history entries don't need modelId based on Python code
					origin: "AI_EDITOR",
				},
			}

			// If this is a tool result message
			if (msg.tool_call_id) {
				entry.userInputMessage!.userInputMessageContext = {
					toolResults: [
						{
							content: [{ text: msg.content || "(empty result)" }],
							status: "success",
							toolUseId: msg.tool_call_id,
						},
					],
				}
			}

			converted.push(entry)
		} else if (msg.role === "assistant") {
			const assistantEntry: KiroHistoryEntry = {
				assistantResponseMessage: {
					content: msg.content ?? "",
				},
			}

			if (msg.tool_calls?.length) {
				assistantEntry.assistantResponseMessage!.toolUses =
					msg.tool_calls.map((tc) => ({
						toolUseId: tc.id,
						name: tc.function.name,
						input: safeJsonParse(tc.function.arguments),
					}))
			}

			converted.push(assistantEntry)
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
						description: tool.function.description || `Tool: ${tool.function.name}`,
						inputSchema: {
							json: sanitizeJsonSchema(
								tool.function.parameters ?? {
									type: "object",
									properties: {},
								},
							),
						},
					},
				}
			}
			if (tool.name) {
				return {
					toolSpecification: {
						name: tool.name,
						description: tool.description || `Tool: ${tool.name}`,
						inputSchema: {
							json: sanitizeJsonSchema(
								tool.input_schema ?? {
									type: "object",
									properties: {},
								},
							),
						},
					},
				}
			}
			return null
		})
		.filter((t): t is KiroToolSpec => t !== null)
}

// ponytail: Kiro API rejects empty required arrays and additionalProperties
function sanitizeJsonSchema(schema: Record<string, unknown>): Record<string, unknown> {
	const result: Record<string, unknown> = {}

	for (const [key, value] of Object.entries(schema)) {
		if (key === "required" && Array.isArray(value) && value.length === 0) continue
		if (key === "additionalProperties") continue

		if (key === "properties" && typeof value === "object" && value !== null) {
			const props: Record<string, unknown> = {}
			for (const [pName, pValue] of Object.entries(value as Record<string, unknown>)) {
				props[pName] = typeof pValue === "object" && pValue !== null
					? sanitizeJsonSchema(pValue as Record<string, unknown>)
					: pValue
			}
			result[key] = props
		} else if (Array.isArray(value)) {
			result[key] = value.map((item) =>
				typeof item === "object" && item !== null
					? sanitizeJsonSchema(item as Record<string, unknown>)
					: item,
			)
		} else if (typeof value === "object" && value !== null) {
			result[key] = sanitizeJsonSchema(value as Record<string, unknown>)
		} else {
			result[key] = value
		}
	}

	return result
}

function extractTextContent(content: OpenAIMessage["content"]): string {
	if (content === null) return ""
	if (typeof content === "string") return content
	if (Array.isArray(content)) {
		return content
			.filter((b) => b.type === "text" && b.text)
			.map((b) => b.text as string)
			.join("")
	}
	return ""
}

function safeJsonParse(str: string): unknown {
	try {
		return JSON.parse(str)
	} catch {
		return {}
	}
}
