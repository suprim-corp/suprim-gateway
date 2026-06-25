import { eq, sql } from "drizzle-orm"
import { db } from "../db/index"
import { virtualKeys } from "../db/schema"

const KEY_PREFIX = "sk-kiro-"

export interface CreateKeyInput {
	name: string
	accountId?: string
	rateLimitPerMin?: number
	allowedModels?: string[]
}

export interface UpdateKeyInput {
	name?: string
	enabled?: boolean
	accountId?: string | null
	rateLimitPerMin?: number
	allowedModels?: string[] | null
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

	if (Object.keys(updates).length === 0) return getKeyById(id)

	db.update(virtualKeys).set(updates).where(eq(virtualKeys.id, id)).run()
	return getKeyById(id)
}

export function deleteKey(id: string): boolean {
	const result = db.delete(virtualKeys).where(eq(virtualKeys.id, id)).run()
	return result.changes > 0
}

export function recordUsage(id: string, tokens: number): void {
	db.update(virtualKeys)
		.set({
			totalRequests: sql`${virtualKeys.totalRequests} + 1`,
			totalTokens: sql`${virtualKeys.totalTokens} + ${tokens}`,
			lastUsedAt: Date.now(),
		})
		.where(eq(virtualKeys.id, id))
		.run()
}
