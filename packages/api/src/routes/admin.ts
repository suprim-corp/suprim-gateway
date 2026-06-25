import { Elysia } from "elysia"
import { createSession, validateSession } from "../auth/token"
import { env } from "../config"
import { getStats, getTimeSeries, queryLogs } from "../logging"
import {
	createKey,
	deleteKey,
	getKeyById,
	listKeys,
	updateKey,
} from "../virtual-keys"

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
			data: keys.map((k) => ({
				id: k.id,
				name: k.name,
				keyPrefix: k.keyPrefix,
				accountId: k.accountId,
				enabled: k.enabled,
				rateLimitPerMin: k.rateLimitPerMin,
				allowedModels: k.allowedModels
					? JSON.parse(k.allowedModels)
					: null,
				totalRequests: k.totalRequests,
				totalTokens: k.totalTokens,
				lastUsedAt: k.lastUsedAt,
				createdAt: k.createdAt,
			})),
		}
	})
	.post("/keys", async ({ body, set }) => {
		const input = body as {
			name?: string
			accountId?: string
			rateLimitPerMin?: number
			allowedModels?: string[]
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
	.get("/accounts", () => ({ data: [] }))

export const adminRoutes = new Elysia().use(loginRoute).use(protectedRoutes)
