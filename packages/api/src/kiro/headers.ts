import { randomUUID } from "node:crypto"
import type { KiroAuthManager } from "../auth/manager"
import { getMachineFingerprint } from "../utils/ids"

export function buildKiroHeaders(
	_auth: KiroAuthManager,
	token: string,
): Record<string, string> {
	const fingerprint = getMachineFingerprint()
	return {
		Authorization: `Bearer ${token}`,
		"Content-Type": "application/x-amz-json-1.0",
		"x-amz-target":
			"AmazonCodeWhispererStreamingService.GenerateAssistantResponse",
		"User-Agent": `aws-sdk-js/1.0.27 os/darwin arch/arm64 lang/js md/nodejs#22.0.0 KiroIDE-0.7.45-${fingerprint}`,
		"x-amz-user-agent": `aws-sdk-js/1.0.27 KiroIDE-0.7.45-${fingerprint}`,
		"x-amzn-codewhisperer-optout": "true",
		"x-amzn-kiro-agent-mode": "vibe",
		"amz-sdk-invocation-id": randomUUID(),
		"amz-sdk-request": "attempt=1; max=3",
		Connection: "close",
	}
}
