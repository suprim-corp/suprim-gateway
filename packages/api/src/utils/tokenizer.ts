import { encodingForModel } from "js-tiktoken"
import type { OpenAIMessage, OpenAITool } from "../converters/openai-to-kiro"

// Claude tokenizes ~15% more than cl100k_base (empirical)
const CLAUDE_CORRECTION_FACTOR = 1.15

const encoding = encodingForModel("gpt-4o")

export function countTokens(text: string): number {
	if (!text) return 0
	return Math.round(encoding.encode(text).length * CLAUDE_CORRECTION_FACTOR)
}

export function countMessageTokens(messages: OpenAIMessage[]): number {
	if (!messages.length) return 0
	let total = 0
	
	for (const msg of messages) {
		total += 4 // role + delimiters
		if (typeof msg.content === "string") {
			total += encoding.encode(msg.content).length
		} else if (Array.isArray(msg.content)) {
			for (const block of msg.content) {
				if (block.type === "text" && block.text) {
					total += encoding.encode(block.text).length
				} else if (block.type === "image_url") {
					total += 100
				}
			}
		}
		if (msg.tool_calls) {
			for (const tc of msg.tool_calls) {
				total += 4
				total += encoding.encode(tc.function.name).length
				total += encoding.encode(tc.function.arguments).length
			}
		}
		if (msg.tool_call_id) {
			total += encoding.encode(msg.tool_call_id).length
		}
	}
	
	total += 3 // final service tokens
	return Math.round(total * CLAUDE_CORRECTION_FACTOR)
}

export function countToolsTokens(tools: OpenAITool[] | undefined): number {
	if (!tools?.length) return 0
	let total = 0
	
	for (const tool of tools) {
		total += 4
		const payload = tool.type === "function" && tool.function ? tool.function : tool
		if (payload.name) total += encoding.encode(payload.name).length
		if (payload.description) total += encoding.encode(payload.description).length
		const params = (payload as any).input_schema ?? (payload as any).parameters
		if (params) total += encoding.encode(JSON.stringify(params)).length
	}
	
	return Math.round(total * CLAUDE_CORRECTION_FACTOR)
}

export function estimateRequestTokens(
	messages: OpenAIMessage[],
	tools?: OpenAITool[],
): number {
	return countMessageTokens(messages) + countToolsTokens(tools)
}
