import { eq, sql } from "drizzle-orm"
import { db } from "../db"
import { virtualKeys } from "../db/schema"

const KEY_PREFIX = "sk-"

export interface CreateKeyInput {
	name: string
	accountId?: string
	rateLimitPerMin?: number
	allowedModels?: string[]
	budgetPeriod?: string | null
	budgetTokens?: number | null
	budgetRequests?: number | null
}

export interface UpdateKeyInput {
	name?: string
	enabled?: boolean
	accountId?: string | null
	rateLimitPerMin?: number
	allowedModels?: string[] | null
	budgetPeriod?: string | null
	budgetTokens?: number | null
	budgetRequests?: number | null
}

export interface VirtualKeyRow {
	id: string
	name: string
	keyHash: string
	keyPrefix: string
	accountId: string | null
	enabled: boolean
	rateLimitPerMin: number
	allowedModels: string | null
	budgetPeriod: string | null
	budgetTokens: number | null
	budgetRequests: number | null
	periodTokensUsed: number
	periodRequestsUsed: number
	periodResetAt: number | null
	totalRequests: number
	totalTokens: number
	lastUsedAt: number | null
	createdAt: number
}

function generateKeyId(): string {
	return `vk_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`
}

function generateRawKey(): string {
	const random =
		crypto.randomUUID().replace(/-/g, "") +
		crypto.randomUUID().replace(/-/g, "")
	return `${KEY_PREFIX}${random.slice(0, 32)}`
}

async function hashKey(raw: string): Promise<string> {
	const data = new TextEncoder().encode(raw)
	const hash = await crypto.subtle.digest("SHA-256", data)
	return Array.from(new Uint8Array(hash))
		.map((b) => b.toString(16).padStart(2, "0"))
		.join("")
}

export async function createKey(
	input: CreateKeyInput,
): Promise<{ key: VirtualKeyRow; rawKey: string }> {
	const id = generateKeyId()
	const rawKey = generateRawKey()
	const keyHash = await hashKey(rawKey)
	const keyPrefix = rawKey.slice(0, KEY_PREFIX.length + 8)

	const row = {
		id,
		name: input.name,
		keyHash,
		keyPrefix,
		accountId: input.accountId ?? null,
		enabled: true,
		rateLimitPerMin: input.rateLimitPerMin ?? 60,
		allowedModels: input.allowedModels
			? JSON.stringify(input.allowedModels)
			: null,
		budgetPeriod: input.budgetPeriod ?? null,
		budgetTokens: input.budgetTokens ?? null,
		budgetRequests: input.budgetRequests ?? null,
		periodTokensUsed: 0,
		periodRequestsUsed: 0,
		periodResetAt: null,
		totalRequests: 0,
		totalTokens: 0,
		lastUsedAt: null,
		createdAt: Date.now(),
	}

	db.insert(virtualKeys).values(row).run()

	return { key: row, rawKey }
}

export function listKeys(): VirtualKeyRow[] {
	return db.select().from(virtualKeys).all() as VirtualKeyRow[]
}

export function getKeyById(id: string): VirtualKeyRow | undefined {
	return db.select().from(virtualKeys).where(eq(virtualKeys.id, id)).get() as
		| VirtualKeyRow
		| undefined
}

export async function getKeyByRawKey(
	rawKey: string,
): Promise<VirtualKeyRow | undefined> {
	const keyHash = await hashKey(rawKey)
	return db
		.select()
		.from(virtualKeys)
		.where(eq(virtualKeys.keyHash, keyHash))
		.get() as VirtualKeyRow | undefined
}

export function updateKey(
	id: string,
	input: UpdateKeyInput,
): VirtualKeyRow | undefined {
	const updates: Record<string, unknown> = {}
	if (input.name !== undefined) updates.name = input.name
	if (input.enabled !== undefined) updates.enabled = input.enabled
	if (input.accountId !== undefined) updates.accountId = input.accountId
	if (input.rateLimitPerMin !== undefined)
		updates.rateLimitPerMin = input.rateLimitPerMin
	if (input.allowedModels !== undefined) {
		updates.allowedModels = input.allowedModels
			? JSON.stringify(input.allowedModels)
			: null
	}
	if (input.budgetPeriod !== undefined)
		updates.budgetPeriod = input.budgetPeriod
	if (input.budgetTokens !== undefined)
		updates.budgetTokens = input.budgetTokens
	if (input.budgetRequests !== undefined)
		updates.budgetRequests = input.budgetRequests
	if (input.budgetPeriod !== undefined) {
		updates.periodTokensUsed = 0
		updates.periodRequestsUsed = 0
		updates.periodResetAt = input.budgetPeriod ? getNextPeriodReset(input.budgetPeriod) : null
	}

	if (Object.keys(updates).length === 0) return getKeyById(id)

	db.update(virtualKeys).set(updates).where(eq(virtualKeys.id, id)).run()
	return getKeyById(id)
}

export function deleteKey(id: string): boolean {
	const before = getKeyById(id)
	if (!before) return false
	db.delete(virtualKeys).where(eq(virtualKeys.id, id)).run()
	return true
}

export function recordUsage(id: string, tokens: number): void {
	db.update(virtualKeys)
		.set({
			totalRequests: sql`${virtualKeys.totalRequests} + 1`,
			totalTokens: sql`${virtualKeys.totalTokens} + ${tokens}`,
			periodRequestsUsed: sql`${virtualKeys.periodRequestsUsed} + 1`,
			periodTokensUsed: sql`${virtualKeys.periodTokensUsed} + ${tokens}`,
			lastUsedAt: Date.now(),
		})
		.where(eq(virtualKeys.id, id))
		.run()
}

function getNextPeriodReset(period: string): number {
	const now = new Date()
	switch (period) {
		case "hour":
			return new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours() + 1).getTime()
		case "day":
			return new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime()
		case "week": {
			const day = now.getDay()
			const diff = now.getDate() - day + (day === 0 ? -6 : 1) + 7
			return new Date(now.getFullYear(), now.getMonth(), diff).getTime()
		}
		case "month":
			return new Date(now.getFullYear(), now.getMonth() + 1, 1).getTime()
		default:
			return 0
	}
}

function resetPeriodIfNeeded(key: VirtualKeyRow): VirtualKeyRow {
	if (!key.budgetPeriod) return key
	const now = Date.now()
	if (!key.periodResetAt || now >= key.periodResetAt) {
		const nextReset = getNextPeriodReset(key.budgetPeriod)
		db.update(virtualKeys)
			.set({ periodTokensUsed: 0, periodRequestsUsed: 0, periodResetAt: nextReset })
			.where(eq(virtualKeys.id, key.id))
			.run()
		return { ...key, periodTokensUsed: 0, periodRequestsUsed: 0, periodResetAt: nextReset }
	}
	return key
}

export interface BudgetUsage {
	tokens: number
	requests: number
}

export function getBudgetUsage(keyId: string, period: string): BudgetUsage {
	const key = getKeyById(keyId)
	if (!key) return { tokens: 0, requests: 0 }
	const fresh = resetPeriodIfNeeded(key)
	return { tokens: fresh.periodTokensUsed, requests: fresh.periodRequestsUsed }
}

export function checkBudget(key: VirtualKeyRow): { allowed: boolean; reason?: string } {
	if (!key.budgetPeriod) return { allowed: true }
	const fresh = resetPeriodIfNeeded(key)

	if (fresh.budgetTokens != null && fresh.periodTokensUsed >= fresh.budgetTokens) {
		return { allowed: false, reason: `Token budget exceeded (${fresh.periodTokensUsed}/${fresh.budgetTokens} per ${fresh.budgetPeriod})` }
	}
	if (fresh.budgetRequests != null && fresh.periodRequestsUsed >= fresh.budgetRequests) {
		return { allowed: false, reason: `Request budget exceeded (${fresh.periodRequestsUsed}/${fresh.budgetRequests} per ${fresh.budgetPeriod})` }
	}
	return { allowed: true }
}
