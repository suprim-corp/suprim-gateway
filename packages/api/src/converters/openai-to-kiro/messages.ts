import { extractImages, extractTextContent } from "./content"
import { logger } from "../../logging/logger"
import type { KiroHistoryEntry, KiroImageBlock, OpenAIMessage } from "./types"

export function convertMessages(messages: OpenAIMessage[]): {
	system: string | null
	messages: KiroHistoryEntry[]
} {
	let system: string | null = null
	const converted: KiroHistoryEntry[] = []

	interface Turn {
		role: "user" | "assistant"
		content: string
		images?: KiroImageBlock[]
		tool_calls?: OpenAIMessage["tool_calls"]
		tool_results?: Array<{ tool_call_id: string; content: string }>
	}

	const turns: Turn[] = []

	for (let i = 0; i < messages.length; i++) {
		const msg = messages[i]

		if (msg.role === "system") {
			const text = extractTextContent(msg.content)
			system = system ? `${system}\n\n${text}` : text
			continue
		}

		if (msg.role === "tool") {
			const last = turns[turns.length - 1]
			if (last && last.role === "user" && last.tool_results) {
				last.tool_results.push({
					tool_call_id: msg.tool_call_id ?? "",
					content: extractTextContent(msg.content) || "(empty result)",
				})
			} else {
				turns.push({
					role: "user",
					content: "",
					tool_results: [{
						tool_call_id: msg.tool_call_id ?? "",
						content: extractTextContent(msg.content) || "(empty result)",
					}],
				})
			}
			continue
		}

		if (msg.role === "assistant") {
			turns.push({
				role: "assistant",
				content: extractTextContent(msg.content) ?? "",
				tool_calls: msg.tool_calls,
			})
			continue
		}

		turns.push({
			role: "user",
			content: extractTextContent(msg.content) ?? "",
			images: extractImages(msg.content),
		})
	}

	// Ensure first message is user
	if (turns.length > 0 && turns[0].role !== "user") {
		turns.unshift({ role: "user", content: "(empty placeholder)" })
	}

	// Ensure alternating roles
	const alternating: Turn[] = []
	for (const turn of turns) {
		if (alternating.length > 0) {
			const last = alternating[alternating.length - 1]
			if (turn.role === last.role) {
				const filler = turn.role === "user" ? "assistant" : "user"
				alternating.push({ role: filler, content: "(empty placeholder)" })
			}
		}
		alternating.push(turn)
	}

	// Convert to Kiro history format with deduplication
	const seenToolUseIds = new Set<string>()
	const emittedToolUseIds = new Set<string>()
	for (const turn of alternating) {
		if (turn.role === "user") {
			const entry: KiroHistoryEntry = {
				userInputMessage: {
					content: turn.content || "(empty placeholder)",
					modelId: "",
					origin: "AI_EDITOR",
					images: turn.images,
				},
			}

			if (turn.tool_results?.length) {
				const seenResultIds = new Set<string>()
				const validResults = turn.tool_results.filter((tr) => {
					if (!emittedToolUseIds.has(tr.tool_call_id)) return false
					if (seenResultIds.has(tr.tool_call_id)) return false
					seenResultIds.add(tr.tool_call_id)
					return true
				})
				if (validResults.length) {
					entry.userInputMessage!.userInputMessageContext = {
						toolResults: validResults.map((tr) => ({
							content: [{ text: tr.content }],
							status: "success",
							toolUseId: tr.tool_call_id,
						})),
					}
				}
			}

			converted.push(entry)
		} else if (turn.role === "assistant") {
			const assistantEntry: KiroHistoryEntry = {
				assistantResponseMessage: {
					content: turn.content ?? "",
				},
			}

			if (turn.tool_calls?.length) {
				const dedupedToolCalls = turn.tool_calls.filter((tc) => {
					if (seenToolUseIds.has(tc.id)) return false
					seenToolUseIds.add(tc.id)
					return true
				})
				if (dedupedToolCalls.length) {
					assistantEntry.assistantResponseMessage!.toolUses =
						dedupedToolCalls.map((tc) => ({
							toolUseId: tc.id,
							name: tc.function.name,
							input: safeJsonParse(tc.function.arguments),
						}))
					for (const tc of dedupedToolCalls) {
						emittedToolUseIds.add(tc.id)
					}
				}
			}

			converted.push(assistantEntry)
		}
	}

	// Enforce: each user turn's toolResults count must not exceed the preceding assistant turn's toolUses count
	for (let i = 1; i < converted.length; i++) {
		const cur = converted[i]
		const prev = converted[i - 1]
		const results = cur.userInputMessage?.userInputMessageContext?.toolResults
		const uses = prev.assistantResponseMessage?.toolUses
		if (results && results.length > 0) {
			const maxAllowed = uses?.length ?? 0
			if (maxAllowed === 0) {
				logger.warn(`[Converter] Dropping ${results.length} orphan toolResults at turn ${i} (no toolUses in prev turn). IDs: ${results.map((r: any) => r.toolUseId).join(", ")}`)
				delete cur.userInputMessage!.userInputMessageContext
			} else if (results.length > maxAllowed) {
				const dropped = results.slice(maxAllowed)
				logger.warn(`[Converter] Trimming toolResults at turn ${i}: ${results.length} results > ${maxAllowed} toolUses. Dropped IDs: ${dropped.map((r: any) => r.toolUseId).join(", ")}`)
				cur.userInputMessage!.userInputMessageContext!.toolResults = results.slice(0, maxAllowed)
			}
		}
	}

	return { system, messages: converted }
}

function safeJsonParse(str: string): unknown {
	try {
		return JSON.parse(str)
	} catch {
		return {}
	}
}
