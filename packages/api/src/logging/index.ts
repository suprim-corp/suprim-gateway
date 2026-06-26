import { desc, eq, sql } from "drizzle-orm"
import { db } from "../db/index"
import { requestLogs, virtualKeys } from "../db/schema"
import { calculateCost, resolveModelAlias } from "../utils/pricing"

export interface LogEntry {
	virtualKeyId?: string
	accountId?: string
	model: string
	requestedModel?: string
	status: number
	promptTokens?: number
	completionTokens?: number
	totalTokens?: number
	latencyMs?: number
	firstTokenMs?: number
	streaming?: boolean
	clientIp?: string
	errorMessage?: string
}

export function logRequest(entry: LogEntry): void {
	const id = `log_${crypto.randomUUID().replace(/-/g, "").slice(0, 16)}`

	db.insert(requestLogs)
		.values({
			id,
			virtualKeyId: entry.virtualKeyId ?? null,
			accountId: entry.accountId ?? null,
			model: entry.model,
			requestedModel: entry.requestedModel ?? null,
			status: entry.status,
			promptTokens: entry.promptTokens ?? null,
			completionTokens: entry.completionTokens ?? null,
			totalTokens: entry.totalTokens ?? null,
			latencyMs: entry.latencyMs ?? null,
			firstTokenMs: entry.firstTokenMs ?? null,
			streaming: entry.streaming ?? null,
			clientIp: entry.clientIp ?? null,
			errorMessage: entry.errorMessage ?? null,
			createdAt: Date.now(),
		})
		.run()
}

export interface LogQuery {
	limit?: number
	offset?: number
	virtualKeyId?: string
	model?: string
	status?: number
}

export interface LogRow {
	id: string
	virtualKeyId: string | null
	virtualKeyName: string | null
	accountId: string | null
	model: string
	requestedModel: string | null
	status: number
	promptTokens: number | null
	completionTokens: number | null
	totalTokens: number | null
	cost: number | null
	latencyMs: number | null
	firstTokenMs: number | null
	streaming: boolean | null
	clientIp: string | null
	errorMessage: string | null
	createdAt: number
}

export function queryLogs(query: LogQuery): { data: LogRow[]; total: number } {
	const limit = query.limit ?? 50
	const offset = query.offset ?? 0

	let base = db
		.select({
			id: requestLogs.id,
			virtualKeyId: requestLogs.virtualKeyId,
			virtualKeyName: virtualKeys.name,
			accountId: requestLogs.accountId,
			model: requestLogs.model,
			requestedModel: requestLogs.requestedModel,
			status: requestLogs.status,
			promptTokens: requestLogs.promptTokens,
			completionTokens: requestLogs.completionTokens,
			totalTokens: requestLogs.totalTokens,
			latencyMs: requestLogs.latencyMs,
			firstTokenMs: requestLogs.firstTokenMs,
			streaming: requestLogs.streaming,
			clientIp: requestLogs.clientIp,
			errorMessage: requestLogs.errorMessage,
			createdAt: requestLogs.createdAt,
		})
		.from(requestLogs)
		.leftJoin(virtualKeys, eq(requestLogs.virtualKeyId, virtualKeys.id))
		.$dynamic()

	if (query.virtualKeyId) {
		base = base.where(eq(requestLogs.virtualKeyId, query.virtualKeyId))
	} else if (query.model) {
		base = base.where(eq(requestLogs.model, query.model))
	} else if (query.status) {
		base = base.where(eq(requestLogs.status, query.status))
	}

	const countResult = db
		.select({ count: sql<number>`count(*)` })
		.from(requestLogs)
		.get() as { count: number } | undefined

	const total = countResult?.count ?? 0

	const rows = base
		.orderBy(desc(requestLogs.createdAt))
		.limit(limit)
		.offset(offset)
		.all() as Omit<LogRow, "cost">[]

	const data: LogRow[] = rows.map((r) => ({
		...r,
		cost: r.promptTokens != null && r.completionTokens != null
			? calculateCost(r.model, r.promptTokens, r.completionTokens)
			: null,
	}))

	return { data, total }
}

export interface TimeSeriesPoint {
	time: string
	requests: number
	tokens: number
	errors: number
	cost: number
}

export function getTimeSeries(hours = 24): TimeSeriesPoint[] {
	const now = Date.now()
	const since = now - hours * 60 * 60 * 1000
	const bucketMs = hours <= 24 ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000

	const rows = db
		.select({
			createdAt: requestLogs.createdAt,
			model: requestLogs.model,
			promptTokens: requestLogs.promptTokens,
			completionTokens: requestLogs.completionTokens,
			totalTokens: requestLogs.totalTokens,
			status: requestLogs.status,
		})
		.from(requestLogs)
		.where(sql`${requestLogs.createdAt} >= ${since}`)
		.all()

	const buckets = new Map<number, { requests: number; tokens: number; errors: number; cost: number }>()

	for (const row of rows) {
		const bucket = Math.floor(row.createdAt / bucketMs) * bucketMs
		const entry = buckets.get(bucket) ?? { requests: 0, tokens: 0, errors: 0, cost: 0 }
		entry.requests++
		entry.tokens += row.totalTokens ?? 0
		if (row.status >= 400) entry.errors++
		entry.cost += calculateCost(row.model, row.promptTokens ?? 0, row.completionTokens ?? 0)
		buckets.set(bucket, entry)
	}

	const points: TimeSeriesPoint[] = []
	let cursor = Math.floor(since / bucketMs) * bucketMs
	while (cursor <= now) {
		const entry = buckets.get(cursor) ?? { requests: 0, tokens: 0, errors: 0, cost: 0 }
		const d = new Date(cursor)
		const label = hours <= 24
			? `${String(d.getHours()).padStart(2, "0")}:00`
			: `${d.getMonth() + 1}/${d.getDate()}`
		points.push({ time: label, ...entry })
		cursor += bucketMs
	}

	return points
}

export interface ModelUsage {
	model: string
	requests: number
	tokens: number
	cost: number
}

export function getModelUsage(): ModelUsage[] {
	const rows = db
		.select({
			model: requestLogs.model,
			requests: sql<number>`count(*)`,
			tokens: sql<number>`coalesce(sum(${requestLogs.totalTokens}), 0)`,
			promptTokens: sql<number>`coalesce(sum(${requestLogs.promptTokens}), 0)`,
			completionTokens: sql<number>`coalesce(sum(${requestLogs.completionTokens}), 0)`,
		})
		.from(requestLogs)
		.groupBy(requestLogs.model)
		.orderBy(sql`count(*) desc`)
		.limit(10)
		.all() as { model: string; requests: number; tokens: number; promptTokens: number; completionTokens: number }[]

	const merged = new Map<string, ModelUsage>()
	for (const r of rows) {
		const name = resolveModelAlias(r.model)
		const existing = merged.get(name)
		if (existing) {
			existing.requests += r.requests
			existing.tokens += r.tokens
			existing.cost += calculateCost(r.model, r.promptTokens, r.completionTokens)
		} else {
			merged.set(name, {
				model: name,
				requests: r.requests,
				tokens: r.tokens,
				cost: calculateCost(r.model, r.promptTokens, r.completionTokens),
			})
		}
	}

	return [...merged.values()].sort((a, b) => b.requests - a.requests)
}

export interface KeyUsage {
	name: string
	requests: number
	tokens: number
}

export function getTopKeys(): KeyUsage[] {
	return db
		.select({
			name: virtualKeys.name,
			requests: sql<number>`count(*)`,
			tokens: sql<number>`coalesce(sum(${requestLogs.totalTokens}), 0)`,
		})
		.from(requestLogs)
		.innerJoin(virtualKeys, eq(requestLogs.virtualKeyId, virtualKeys.id))
		.groupBy(virtualKeys.name)
		.orderBy(sql`count(*) desc`)
		.limit(10)
		.all() as KeyUsage[]
}

export function getKeyCostSince(keyId: string, since: number): number {
	const rows = db
		.select({
			model: requestLogs.model,
			promptTokens: sql<number>`coalesce(sum(${requestLogs.promptTokens}), 0)`,
			completionTokens: sql<number>`coalesce(sum(${requestLogs.completionTokens}), 0)`,
		})
		.from(requestLogs)
		.where(sql`${requestLogs.virtualKeyId} = ${keyId} AND ${requestLogs.createdAt} >= ${since} AND ${requestLogs.status} < 400`)
		.groupBy(requestLogs.model)
		.all() as { model: string; promptTokens: number; completionTokens: number }[]

	return rows.reduce((sum, r) => sum + calculateCost(r.model, r.promptTokens, r.completionTokens), 0)
}

export function getStats(): {
	totalRequests: number
	totalTokens: number
	totalCost: number
	errorRate: number
	avgLatencyMs: number
} {
	const result = db
		.select({
			totalRequests: sql<number>`count(*)`,
			totalTokens: sql<number>`coalesce(sum(${requestLogs.totalTokens}), 0)`,
			errorCount: sql<number>`sum(case when ${requestLogs.status} >= 400 then 1 else 0 end)`,
			avgLatency: sql<number>`coalesce(avg(${requestLogs.latencyMs}), 0)`,
		})
		.from(requestLogs)
		.get() as
		| {
				totalRequests: number
				totalTokens: number
				errorCount: number
				avgLatency: number
		  }
		| undefined

	if (!result || result.totalRequests === 0) {
		return {
			totalRequests: 0,
			totalTokens: 0,
			totalCost: 0,
			errorRate: 0,
			avgLatencyMs: 0,
		}
	}

	const costRows = db
		.select({
			model: requestLogs.model,
			promptTokens: sql<number>`coalesce(sum(${requestLogs.promptTokens}), 0)`,
			completionTokens: sql<number>`coalesce(sum(${requestLogs.completionTokens}), 0)`,
		})
		.from(requestLogs)
		.groupBy(requestLogs.model)
		.all() as { model: string; promptTokens: number; completionTokens: number }[]

	const totalCost = costRows.reduce((sum, r) => sum + calculateCost(r.model, r.promptTokens, r.completionTokens), 0)

	return {
		totalRequests: result.totalRequests,
		totalTokens: result.totalTokens,
		totalCost,
		errorRate: result.errorCount / result.totalRequests,
		avgLatencyMs: Math.round(result.avgLatency),
	}
}
