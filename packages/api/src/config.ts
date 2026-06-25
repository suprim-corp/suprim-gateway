import { existsSync } from "node:fs"
import { resolve } from "node:path"
import { z } from "zod"

// Load .env from project root
const rootEnv = resolve(import.meta.dir, "../../../.env")
if (existsSync(rootEnv)) {
	const file = Bun.file(rootEnv)
	const text = await file.text()
	for (const line of text.split("\n")) {
		const trimmed = line.trim()
		if (!trimmed || trimmed.startsWith("#")) continue
		const eq = trimmed.indexOf("=")
		if (eq === -1) continue
		const key = trimmed.slice(0, eq).trim()
		let val = trimmed.slice(eq + 1).trim()
		if (
			(val.startsWith('"') && val.endsWith('"')) ||
			(val.startsWith("'") && val.endsWith("'"))
		) {
			val = val.slice(1, -1)
		}
		if (!(key in process.env)) {
			process.env[key] = val
		}
	}
}

const envSchema = z.object({
	PORT: z.coerce.number().default(3001),

	// Kiro credentials
	KIRO_CREDS_FILE: z.string().optional(),
	REFRESH_TOKEN: z.string().optional(),
	KIRO_CLI_DB_FILE: z.string().optional(),
	PROFILE_ARN: z.string().optional(),

	// Region
	KIRO_REGION: z.string().default("us-east-1"),
	KIRO_API_REGION: z.string().optional(),

	// Auth
	ADMIN_API_KEY: z.string().min(1, "ADMIN_API_KEY is required"),

	// Proxy
	VPN_PROXY_URL: z.string().optional(),

	// Logging
	LOG_LEVEL: z.enum(["debug", "info", "warn", "error"]).default("info"),
	DEBUG_MODE: z.enum(["off", "errors", "all"]).default("off"),

	// Timeouts
	FIRST_TOKEN_TIMEOUT: z.coerce.number().default(15),
	STREAMING_READ_TIMEOUT: z.coerce.number().default(300),
	FIRST_TOKEN_MAX_RETRIES: z.coerce.number().default(3),
})

export type Env = z.infer<typeof envSchema>

export const env = envSchema.parse(process.env)
