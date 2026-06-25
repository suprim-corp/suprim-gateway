import { existsSync, readFileSync } from "node:fs"
import { resolve } from "node:path"
import type { NextConfig } from "next"

function loadRootEnv(): Record<string, string> {
	const envPath = resolve(__dirname, "../../.env")
	if (!existsSync(envPath)) return {}
	const vars: Record<string, string> = {}
	for (const line of readFileSync(envPath, "utf-8").split("\n")) {
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
		vars[key] = val
	}
	return vars
}

const rootEnv = loadRootEnv()

const nextConfig: NextConfig = {
	env: {
		NEXT_PUBLIC_API_URL:
			process.env.API_URL ??
			rootEnv.API_URL ??
			`http://localhost:${rootEnv.PORT ?? "3001"}`,
		NEXT_PUBLIC_ADMIN_KEY:
			process.env.ADMIN_API_KEY ?? rootEnv.ADMIN_API_KEY ?? "",
	},
}

export default nextConfig
