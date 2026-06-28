import { db } from "./index"

export function runMigrations() {
	const sqlite = (db as unknown as { $client: import("bun:sqlite").Database })
		.$client

	sqlite.run(`
    CREATE TABLE IF NOT EXISTS accounts (
      id TEXT PRIMARY KEY,
      type TEXT NOT NULL,
      path TEXT,
      region TEXT NOT NULL DEFAULT 'us-east-1',
      api_region TEXT,
      enabled INTEGER NOT NULL DEFAULT 1,
      status TEXT NOT NULL DEFAULT 'unknown',
      last_used_at INTEGER,
      failure_count INTEGER NOT NULL DEFAULT 0,
      last_failure_at INTEGER,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS virtual_keys (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      key_hash TEXT NOT NULL UNIQUE,
      key_prefix TEXT NOT NULL,
      account_id TEXT REFERENCES accounts(id),
      enabled INTEGER NOT NULL DEFAULT 1,
      rate_limit_per_min INTEGER NOT NULL DEFAULT 60,
      allowed_models TEXT,
      total_requests INTEGER NOT NULL DEFAULT 0,
      total_tokens INTEGER NOT NULL DEFAULT 0,
      last_used_at INTEGER,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS request_logs (
      id TEXT PRIMARY KEY,
      virtual_key_id TEXT REFERENCES virtual_keys(id),
      account_id TEXT REFERENCES accounts(id),
      model TEXT NOT NULL,
      requested_model TEXT,
      status INTEGER NOT NULL,
      prompt_tokens INTEGER,
      completion_tokens INTEGER,
      total_tokens INTEGER,
      latency_ms INTEGER,
      first_token_ms INTEGER,
      streaming INTEGER,
      error_message TEXT,
      created_at INTEGER NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_request_logs_created_at ON request_logs(created_at DESC);
    CREATE INDEX IF NOT EXISTS idx_request_logs_virtual_key_id ON request_logs(virtual_key_id);
    CREATE INDEX IF NOT EXISTS idx_virtual_keys_key_hash ON virtual_keys(key_hash);
  `)

	// v2: budget columns
	const hasBudgetPeriod = sqlite
		.query("SELECT 1 FROM pragma_table_info('virtual_keys') WHERE name='budget_period'")
		.get()
	if (!hasBudgetPeriod) {
		sqlite.run(`
      ALTER TABLE virtual_keys ADD COLUMN budget_period TEXT;
      ALTER TABLE virtual_keys ADD COLUMN budget_tokens INTEGER;
      ALTER TABLE virtual_keys ADD COLUMN budget_requests INTEGER;
      ALTER TABLE virtual_keys ADD COLUMN period_tokens_used INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE virtual_keys ADD COLUMN period_requests_used INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE virtual_keys ADD COLUMN period_reset_at INTEGER;
    `)
	}

	// v2.1: period counters (if v2 ran without them)
	const hasPeriodTokensUsed = sqlite
		.query("SELECT 1 FROM pragma_table_info('virtual_keys') WHERE name='period_tokens_used'")
		.get()
	if (!hasPeriodTokensUsed) {
		sqlite.run(`
      ALTER TABLE virtual_keys ADD COLUMN period_tokens_used INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE virtual_keys ADD COLUMN period_requests_used INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE virtual_keys ADD COLUMN period_reset_at INTEGER;
    `)
	}
}
