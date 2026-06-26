import { Elysia } from "elysia"
import { createSession, validateSession } from "../auth/token"
import { env } from "../config"
import { getModelUsage, getStats, getTimeSeries, getTopKeys, getKeyCostSince, queryLogs } from "../logging"
import {
	createKey,
	deleteKey,
	getBudgetUsage,
	getKeyById,
	listKeys,
	updateKey,
} from "../virtual-keys"

function getPeriodMs(period: string | null): number {
	switch (period) {
		case "hour": return 3600_000
		case "day": return 86400_000
		case "week": return 604800_000
		case "month": return 2592000_000
		default: return 0
	}
}

const loginRoute = new Elysia({ prefix: "/admin" }).post(
	"/login",
	({ body, set }) => {
		const { password } = body as { password?: string }
		if (!password || password !== env.ADMIN_API_KEY) {
			set.status = 401
			return { error: "Invalid password" }
		}
		const token = createSession()
		return { token }
	},
)

const protectedRoutes = new Elysia({ prefix: "/admin" })
	.onBeforeHandle(({ headers, set }) => {
		const auth = headers.authorization?.replace("Bearer ", "")
		if (!auth) {
			set.status = 401
			return { error: "Unauthorized" }
		}
		if (auth === env.ADMIN_API_KEY) return
		if (!validateSession(auth)) {
			set.status = 401
			return { error: "Unauthorized" }
		}
	})
	.get("/stats", () => {
		const keys = listKeys()
		const stats = getStats()
		const activeKeys = keys.filter((k) => k.enabled).length

		return {
			...stats,
			activeAccounts: 1,
			activeKeys,
			uptimeSeconds: Math.floor(process.uptime()),
		}
	})
	.get("/logs", ({ query }) => {
		const limit = query.limit ? Number(query.limit) : 50
		const offset = query.offset ? Number(query.offset) : 0
		return queryLogs({
			limit,
			offset,
			virtualKeyId: query.virtualKeyId ?? undefined,
			model: query.model ?? undefined,
			status: query.status ? Number(query.status) : undefined,
		})
	})
	.get("/keys", () => {
		const keys = listKeys()
		return {
			data: keys.map((k) => {
				const periodStart = k.periodResetAt
					? k.periodResetAt - getPeriodMs(k.budgetPeriod)
					: 0
				return {
					id: k.id,
					name: k.name,
					keyPrefix: k.keyPrefix,
					accountId: k.accountId,
					enabled: k.enabled,
					rateLimitPerMin: k.rateLimitPerMin,
					allowedModels: k.allowedModels
						? JSON.parse(k.allowedModels)
						: null,
					budgetPeriod: k.budgetPeriod,
					budgetTokens: k.budgetTokens,
					budgetRequests: k.budgetRequests,
					periodCost: k.budgetPeriod ? getKeyCostSince(k.id, periodStart) : null,
					totalRequests: k.totalRequests,
					totalTokens: k.totalTokens,
					lastUsedAt: k.lastUsedAt,
					createdAt: k.createdAt,
				}
			}),
		}
	})
	.post("/keys", async ({ body, set }) => {
		const input = body as {
			name?: string
			accountId?: string
			rateLimitPerMin?: number
			allowedModels?: string[]
			budgetPeriod?: string | null
			budgetTokens?: number | null
			budgetRequests?: number | null
		}

		if (!input.name) {
			set.status = 400
			return { error: "name is required" }
		}

		const { key, rawKey } = await createKey({
			name: input.name,
			accountId: input.accountId,
			rateLimitPerMin: input.rateLimitPerMin,
			allowedModels: input.allowedModels,
			budgetPeriod: input.budgetPeriod,
			budgetTokens: input.budgetTokens,
			budgetRequests: input.budgetRequests,
		})

		set.status = 201
		return {
			id: key.id,
			name: key.name,
			key: rawKey,
			keyPrefix: key.keyPrefix,
			enabled: key.enabled,
			rateLimitPerMin: key.rateLimitPerMin,
			allowedModels: key.allowedModels
				? JSON.parse(key.allowedModels)
				: null,
			budgetPeriod: key.budgetPeriod,
			budgetTokens: key.budgetTokens,
			budgetRequests: key.budgetRequests,
			createdAt: key.createdAt,
		}
	})
	.patch("/keys/:id", ({ params, body, set }) => {
		const existing = getKeyById(params.id)
		if (!existing) {
			set.status = 404
			return { error: "Key not found" }
		}

		const input = body as {
			name?: string
			enabled?: boolean
			accountId?: string | null
			rateLimitPerMin?: number
			allowedModels?: string[] | null
			budgetPeriod?: string | null
			budgetTokens?: number | null
			budgetRequests?: number | null
		}

		const updated = updateKey(params.id, input)
		if (!updated) {
			set.status = 500
			return { error: "Update failed" }
		}

		return {
			id: updated.id,
			name: updated.name,
			keyPrefix: updated.keyPrefix,
			enabled: updated.enabled,
			rateLimitPerMin: updated.rateLimitPerMin,
			allowedModels: updated.allowedModels
				? JSON.parse(updated.allowedModels)
				: null,
			budgetPeriod: updated.budgetPeriod,
			budgetTokens: updated.budgetTokens,
			budgetRequests: updated.budgetRequests,
			totalRequests: updated.totalRequests,
			totalTokens: updated.totalTokens,
			lastUsedAt: updated.lastUsedAt,
			createdAt: updated.createdAt,
		}
	})
	.delete("/keys/:id", ({ params, set }) => {
		const deleted = deleteKey(params.id)
		if (!deleted) {
			set.status = 404
			return { error: "Key not found" }
		}
		return { success: true }
	})
	.get("/stats/timeseries", ({ query }) => {
		const hours = query.hours ? Number(query.hours) : 24
		return { data: getTimeSeries(hours) }
	})
	.get("/stats/models", () => {
		return { data: getModelUsage() }
	})
	.get("/stats/top-keys", () => {
		return { data: getTopKeys() }
	})
	.get("/accounts", () => ({ data: [] }))
	.get("/keys/:id/budget", ({ params, set }) => {
		const key = getKeyById(params.id)
		if (!key) {
			set.status = 404
			return { error: "Key not found" }
		}
		if (!key.budgetPeriod) {
			return { budgetPeriod: null, tokens: { used: 0, limit: null }, requests: { used: 0, limit: null } }
		}
		const usage = getBudgetUsage(key.id, key.budgetPeriod)
		return {
			budgetPeriod: key.budgetPeriod,
			tokens: { used: usage.tokens, limit: key.budgetTokens },
			requests: { used: usage.requests, limit: key.budgetRequests },
		}
	})

export const adminRoutes = new Elysia().use(loginRoute).use(protectedRoutes)
