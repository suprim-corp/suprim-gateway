import type { KiroAuthManager } from "../../auth/manager"
import { normalizeModelName } from "../../models/resolver"
import { convertMessages } from "./messages"
import { convertTools } from "./tools"
import type {
	ChatCompletionRequest,
	KiroPayload,
	KiroUserInputMessage,
} from "./types"

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
				// strip base64 images from history to avoid re-sending megabytes every turn
				delete entry.userInputMessage.images
			}
		}
		payload.conversationState.history = history
	}

	if (auth.profileArn) {
		payload.profileArn = auth.profileArn
	}

	return payload
}
