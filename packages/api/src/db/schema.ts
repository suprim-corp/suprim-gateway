import { integer, sqliteTable, text } from "drizzle-orm/sqlite-core"

export const accounts = sqliteTable("accounts", {
	id: text("id").primaryKey(),
	type: text("type").notNull(), // 'json' | 'sqlite' | 'refresh_token'
	path: text("path"),
	region: text("region").notNull().default("us-east-1"),
	apiRegion: text("api_region"),
	enabled: integer("enabled", { mode: "boolean" }).notNull().default(true),
	status: text("status").notNull().default("unknown"), // 'healthy' | 'degraded' | 'broken' | 'unknown'
	lastUsedAt: integer("last_used_at"),
	failureCount: integer("failure_count").notNull().default(0),
	lastFailureAt: integer("last_failure_at"),
	createdAt: integer("created_at").notNull(),
})

export const virtualKeys = sqliteTable("virtual_keys", {
	id: text("id").primaryKey(),
	name: text("name").notNull(),
	keyHash: text("key_hash").notNull().unique(),
	keyPrefix: text("key_prefix").notNull(),
	accountId: text("account_id").references(() => accounts.id),
	enabled: integer("enabled", { mode: "boolean" }).notNull().default(true),
	rateLimitPerMin: integer("rate_limit_per_min").notNull().default(60),
	allowedModels: text("allowed_models"), // JSON array or null
	budgetPeriod: text("budget_period"), // 'hour' | 'day' | 'week' | 'month' | null (unlimited)
	budgetTokens: integer("budget_tokens"), // null = unlimited
	budgetRequests: integer("budget_requests"), // null = unlimited
	totalRequests: integer("total_requests").notNull().default(0),
	totalTokens: integer("total_tokens").notNull().default(0),
	lastUsedAt: integer("last_used_at"),
	createdAt: integer("created_at").notNull(),
})

export const requestLogs = sqliteTable("request_logs", {
	id: text("id").primaryKey(),
	virtualKeyId: text("virtual_key_id").references(() => virtualKeys.id),
	accountId: text("account_id").references(() => accounts.id),
	model: text("model").notNull(),
	requestedModel: text("requested_model"),
	status: integer("status").notNull(),
	promptTokens: integer("prompt_tokens"),
	completionTokens: integer("completion_tokens"),
	totalTokens: integer("total_tokens"),
	latencyMs: integer("latency_ms"),
	firstTokenMs: integer("first_token_ms"),
	streaming: integer("streaming", { mode: "boolean" }),
	clientIp: text("client_ip"),
	errorMessage: text("error_message"),
	createdAt: integer("created_at").notNull(),
})
