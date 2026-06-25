import { desc, eq, sql } from "drizzle-orm"
import { db } from "../db/index"
import { requestLogs, virtualKeys } from "../db/schema"

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
	latencyMs: number | null
	firstTokenMs: number | null
	streaming: boolean | null
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

	const data = base
		.orderBy(desc(requestLogs.createdAt))
		.limit(limit)
		.offset(offset)
		.all() as LogRow[]

	return { data, total }
}

export interface TimeSeriesPoint {
	time: string
	requests: number
	tokens: number
	errors: number
}

export function getTimeSeries(hours = 24): TimeSeriesPoint[] {
	const now = Date.now()
	const since = now - hours * 60 * 60 * 1000
	const bucketMs = hours <= 24 ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000

	const rows = db
		.select({
			createdAt: requestLogs.createdAt,
			totalTokens: requestLogs.totalTokens,
			status: requestLogs.status,
		})
		.from(requestLogs)
		.where(sql`${requestLogs.createdAt} >= ${since}`)
		.all()

	const buckets = new Map<number, { requests: number; tokens: number; errors: number }>()

	for (const row of rows) {
		const bucket = Math.floor(row.createdAt / bucketMs) * bucketMs
		const entry = buckets.get(bucket) ?? { requests: 0, tokens: 0, errors: 0 }
		entry.requests++
		entry.tokens += row.totalTokens ?? 0
		if (row.status >= 400) entry.errors++
		buckets.set(bucket, entry)
	}

	const points: TimeSeriesPoint[] = []
	let cursor = Math.floor(since / bucketMs) * bucketMs
	while (cursor <= now) {
		const entry = buckets.get(cursor) ?? { requests: 0, tokens: 0, errors: 0 }
		const d = new Date(cursor)
		const label = hours <= 24
			? `${String(d.getHours()).padStart(2, "0")}:00`
			: `${d.getMonth() + 1}/${d.getDate()}`
		points.push({ time: label, ...entry })
		cursor += bucketMs
	}

	return points
}

export function getStats(): {
	totalRequests: number
	totalTokens: number
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
			errorRate: 0,
			avgLatencyMs: 0,
		}
	}

	return {
		totalRequests: result.totalRequests,
		totalTokens: result.totalTokens,
		errorRate: result.errorCount / result.totalRequests,
		avgLatencyMs: Math.round(result.avgLatency),
	}
}
