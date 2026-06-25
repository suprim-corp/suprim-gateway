import { createHash, randomUUID } from "node:crypto"
import { hostname, userInfo } from "node:os"

let cachedFingerprint: string | null = null

export function getMachineFingerprint(): string {
	if (cachedFingerprint) return cachedFingerprint
	const raw = `${hostname()}-${userInfo().username}-kiro-gateway`
	cachedFingerprint = createHash("sha256")
		.update(raw)
		.digest("hex")
		.slice(0, 12)
	return cachedFingerprint
}

export function generateCompletionId(): string {
	return `chatcmpl-${randomUUID().replace(/-/g, "")}`
}

export function generateToolCallId(): string {
	return `call_${randomUUID().replace(/-/g, "").slice(0, 8)}`
}

export function generateConversationId(): string {
	return randomUUID().replace(/-/g, "").slice(0, 16)
}
