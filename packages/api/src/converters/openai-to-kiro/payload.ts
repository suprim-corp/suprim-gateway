import type { KiroAuthManager } from "../../auth/manager"
import { logger } from "../../logging/logger"
import { normalizeModelName } from "../../models/resolver"
import { convertMessages } from "./messages"
import { convertTools } from "./tools"
import type {
	ChatCompletionRequest,
	KiroPayload,
	KiroUserInputMessage,
} from "./types"

const MAX_PAYLOAD_BYTES = 600_000

export function buildKiroPayload(
	req: ChatCompletionRequest,
	auth: KiroAuthManager,
): KiroPayload {
	const { system, messages } = convertMessages(req.messages)
	const modelId = normalizeModelName(req.model)
	const conversationId = crypto.randomUUID()

	let currentContent =
		messages[messages.length - 1]?.userInputMessage?.content ?? ""

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

	// Carry over images from the last (current) message
	const lastEntry = messages[messages.length - 1]
	if (lastEntry?.userInputMessage?.images) {
		userInputMessage.images = lastEntry.userInputMessage.images
	}

	// Carry over toolResults from the last (current) message
	if (lastEntry?.userInputMessage?.userInputMessageContext?.toolResults) {
		if (!userInputMessage.userInputMessageContext) {
			userInputMessage.userInputMessageContext = {}
		}
		userInputMessage.userInputMessageContext.toolResults =
			lastEntry.userInputMessage.userInputMessageContext.toolResults
	}

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

	if (messages.length > 1) {
		const history = messages.slice(0, -1)
		if (system && history.length > 0 && history[0].userInputMessage) {
			history[0].userInputMessage.content = `${system}\n\n${history[0].userInputMessage.content}`
		}
		for (const entry of history) {
			if (entry.userInputMessage) {
				entry.userInputMessage.modelId = modelId
				delete entry.userInputMessage.images
			}
		}
		payload.conversationState.history = history
	}

	if (auth.profileArn) {
		payload.profileArn = auth.profileArn
	}

	// trim oldest history pairs if payload exceeds Kiro's ~615kb limit
	trimPayload(payload)

	return payload
}

function trimPayload(payload: KiroPayload): void {
	const history = payload.conversationState.history
	if (!history?.length) return

	const originalLen = history.length
	while (history.length > 2 && JSON.stringify(payload).length > MAX_PAYLOAD_BYTES) {
		history.shift()
		history.shift()
	}

	// Ensure history starts with a userInputMessage
	while (history.length && !history[0].userInputMessage) {
		history.shift()
	}

	// Repair orphaned toolResults — remove any that reference toolUseIds
	// not present in the preceding assistant message
	if (history.length !== originalLen) {
		for (let i = 0; i < history.length; i++) {
			const user = history[i].userInputMessage
			if (!user?.userInputMessageContext?.toolResults) continue

			const validIds = new Set<string>()
			if (i > 0) {
				const prev = history[i - 1].assistantResponseMessage
				if (prev?.toolUses) {
					for (const tu of prev.toolUses) {
						if (tu.toolUseId) validIds.add(tu.toolUseId)
					}
				}
			}

			const kept = user.userInputMessageContext.toolResults.filter(
				(tr: { toolUseId?: string }) => validIds.has(tr.toolUseId ?? "")
			)

			if (kept.length) {
				user.userInputMessageContext.toolResults = kept
			} else {
				delete user.userInputMessageContext.toolResults
				if (!Object.keys(user.userInputMessageContext).length) {
					delete user.userInputMessageContext
				}
			}
		}

		logger.info(`Trimmed history: ${originalLen} -> ${history.length} entries`)
	}

	if (!history.length) {
		delete payload.conversationState.history
	}
}
