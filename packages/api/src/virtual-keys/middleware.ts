import { env } from "../config"
import { isRateLimited } from "./rate-limiter"
import { checkBudget, getKeyByRawKey, type VirtualKeyRow } from "./service"

export interface AuthResult {
	type: "admin" | "virtual_key"
	key?: VirtualKeyRow
}

export async function resolveAuth(
	authorization: string | undefined,
): Promise<AuthResult | null> {
	if (!authorization) return null

	const token = authorization.startsWith("Bearer ")
		? authorization.slice(7)
		: authorization

	if (!token) return null

	if (token === env.ADMIN_API_KEY) {
		return { type: "admin" }
	}

	if (token.startsWith("sk-")) {
		const key = await getKeyByRawKey(token)
		if (!key) return null
		if (!key.enabled) return null
		return { type: "virtual_key", key }
	}

	return null
}

export function checkRateLimit(key: VirtualKeyRow): boolean {
	return isRateLimited(key.id, key.rateLimitPerMin)
}

export function checkModelAccess(key: VirtualKeyRow, model: string): boolean {
	if (!key.allowedModels) return true
	const allowed: string[] = JSON.parse(key.allowedModels)
	if (allowed.length === 0) return true
	return allowed.includes(model)
}

export function checkKeyBudget(key: VirtualKeyRow): { allowed: boolean; reason?: string } {
	return checkBudget(key)
}
