import { existsSync, readFileSync, writeFileSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { env } from "../config"
import { logger } from "../logging/logger"
import { refreshDesktopToken, refreshSsoOidcToken } from "./refresh"
import type { AccountCredentialConfig, KiroCredentials } from "./types"
import { AuthType } from "./types"

const TOKEN_REFRESH_THRESHOLD = 600 // 10 min before expiry
const REFRESH_COOLDOWN_MS = 60_000 // don't retry refresh for 1min after failure

export class KiroAuthManager {
	private accessToken: string | null = null
	private refreshToken: string | null = null
	private expiresAt: Date | null = null
	readonly profileArn: string | null = null
	private clientId: string | null = null
	private clientSecret: string | null = null
	private scopes: string[] | null = null
	private authType: AuthType = AuthType.KIRO_DESKTOP
	private refreshing: Promise<void> | null = null
	private lastRefreshFailure = 0
	private readonly credentialSource: { type: "json" | "sqlite"; path: string } | null =
		null
	
	readonly region: string
	readonly apiRegion: string
	
	constructor(config: AccountCredentialConfig) {
		this.region = config.region ?? env.KIRO_REGION
		this.apiRegion = config.api_region ?? env.KIRO_API_REGION ?? this.region
		;(this as { profileArn: string | null }).profileArn =
			config.profile_arn ?? env.PROFILE_ARN ?? null
		
		if (config.type === "json" && config.path) {
			this.credentialSource = { type: "json", path: config.path }
			this.loadFromJson(config.path)
		} else if (config.type === "sqlite" && config.path) {
			this.credentialSource = { type: "sqlite", path: config.path }
			this.loadFromSqlite(config.path)
		} else if (config.type === "refresh_token" && config.refresh_token) {
			this.refreshToken = config.refresh_token
		}
		
		this.detectAuthType()
	}
	
	get apiHost(): string {
		return `https://runtime.${this.apiRegion}.kiro.dev`
	}

	get qHost(): string {
		return `https://q.${this.apiRegion}.amazonaws.com`
	}
	
	private loadFromJson(filePath: string) {
		const resolved = resolve(
			filePath.replace(/^~/, process.env.HOME ?? "~"),
		)
		if (!existsSync(resolved)) {
			console.error(`[Auth] Credentials file not found: ${resolved}`)
			return
		}

		const raw = readFileSync(resolved, "utf-8")
		const creds: KiroCredentials = JSON.parse(raw)

		this.accessToken = creds.accessToken ?? null
		this.refreshToken = creds.refreshToken ?? null
		this.expiresAt = creds.expiresAt ? new Date(creds.expiresAt) : null
		if (creds.profileArn) {
			;(this as { profileArn: string | null }).profileArn =
				creds.profileArn
		}
		this.clientId = creds.clientId ?? null
		this.clientSecret = creds.clientSecret ?? null

		if (!this.clientId && creds.clientIdHash) {
			this.loadEnterpriseDeviceRegistration(creds.clientIdHash)
		}
	}
	
	private loadFromSqlite(dbPath: string) {
		const resolved = resolve(dbPath.replace(/^~/, process.env.HOME ?? "~"))
		logger.info(`[Auth] Opening SQLite: ${resolved} (exists=${existsSync(resolved)})`)
		if (!existsSync(resolved)) {
			logger.error(`[Auth] SQLite database not found: ${resolved}`)
			return
		}

		const { Database } =
			require("bun:sqlite") as typeof import("bun:sqlite")
		const db = new Database(resolved)
		// Force reading latest WAL data from other processes
		db.run("PRAGMA wal_checkpoint(PASSIVE)")

		const tokenKeys = [
			"kirocli:social:token",
			"kirocli:odic:token",
			"codewhisperer:odic:token",
		]

		interface DbRow {
			value: string
		}

		interface TokenData {
			accessToken?: string
			access_token?: string
			refreshToken?: string
			refresh_token?: string
			expiresAt?: string
			expires_at?: string
			scopes?: string[]
		}

		interface RegData {
			clientId?: string
			client_id?: string
			clientSecret?: string
			client_secret?: string
			scopes?: string[]
		}

		let tokenData: TokenData | null = null
		let matchedKey: string | null = null
		for (const key of tokenKeys) {
			const row = db
				.query("SELECT value FROM auth_kv WHERE key = ?")
				.get(key) as DbRow | null
			if (row?.value) {
				tokenData = JSON.parse(row.value) as TokenData
				matchedKey = key
				break
			}
		}

		if (!tokenData) {
			db.close()
			throw new Error("No token found in SQLite database")
		}

		this.accessToken = tokenData.accessToken ?? tokenData.access_token ?? null
		this.refreshToken = tokenData.refreshToken ?? tokenData.refresh_token ?? null
		const expiresRaw = tokenData.expiresAt ?? tokenData.expires_at
		this.expiresAt = expiresRaw ? new Date(expiresRaw) : null
		if (tokenData.scopes) this.scopes = tokenData.scopes

		logger.info(`[Auth] Loaded token from SQLite (key=${matchedKey}, expires=${this.expiresAt?.toISOString()})`)

		// Try loading device registration for SSO OIDC
		const regKeys = [
			"kirocli:odic:device-registration",
			"codewhisperer:odic:device-registration",
		]
		for (const key of regKeys) {
			const row = db
				.query("SELECT value FROM auth_kv WHERE key = ?")
				.get(key) as DbRow | null
			if (row?.value) {
				const reg = JSON.parse(row.value) as RegData
				this.clientId = reg.clientId ?? reg.client_id ?? null
				this.clientSecret = reg.clientSecret ?? reg.client_secret ?? null
				this.scopes = reg.scopes ?? null
				logger.info(`[Auth] Loaded device registration (key=${key}, scopes=${this.scopes?.join(",")})`)
				break
			}
		}

		db.close()
	}
	
	private reloadCredentials() {
		if (!this.credentialSource) return
		if (this.credentialSource.type === "json") {
			this.loadFromJson(this.credentialSource.path)
		} else {
			this.loadFromSqlite(this.credentialSource.path)
		}
		this.detectAuthType()
	}

	private loadEnterpriseDeviceRegistration(clientIdHash: string) {
		const candidates = [
			resolve(process.env.HOME ?? "~", ".aws/sso/cache", `${clientIdHash}.json`),
			resolve(this.credentialSource?.path ? dirname(this.credentialSource.path) : ".", `${clientIdHash}.json`),
		]
		for (const regPath of candidates) {
			if (!existsSync(regPath)) continue
			try {
				const data = JSON.parse(readFileSync(regPath, "utf-8"))
				if (data.clientId) this.clientId = data.clientId
				if (data.clientSecret) this.clientSecret = data.clientSecret
				return
			} catch {}
		}
	}

	private detectAuthType() {
		if (this.clientId && this.clientSecret) {
			this.authType = AuthType.AWS_SSO_OIDC
		} else {
			this.authType = AuthType.KIRO_DESKTOP
		}
	}
	
	private isExpired(): boolean {
		if (!this.expiresAt) return true
		const thresholdMs = TOKEN_REFRESH_THRESHOLD * 1000
		return Date.now() >= this.expiresAt.getTime() - thresholdMs
	}
	
	private isActuallyExpired(): boolean {
		if (!this.expiresAt) return true
		return Date.now() >= this.expiresAt.getTime()
	}
	
	async getAccessToken(): Promise<string> {
		if (this.accessToken && !this.isExpired()) {
			return this.accessToken
		}

		logger.debug(`[Auth] Token expired or missing, expiresAt=${this.expiresAt?.toISOString()}, authType=${this.authType}`)

		// Re-read credentials from disk — Kiro Desktop may have refreshed them
		if (this.credentialSource) {
			this.reloadCredentials()
			if (this.accessToken && !this.isExpired()) {
				logger.info(`[Auth] Reloaded valid token from ${this.credentialSource.type} (expires ${this.expiresAt?.toISOString()})`)
				return this.accessToken
			}
			logger.warn(`[Auth] Reloaded credentials still expired (expires ${this.expiresAt?.toISOString()})`)
		}

		// Skip refresh if we recently failed and token is still usable
		if (Date.now() - this.lastRefreshFailure < REFRESH_COOLDOWN_MS) {
			if (this.accessToken && !this.isActuallyExpired()) {
				logger.debug("[Auth] In cooldown after recent failure, using existing token")
				return this.accessToken
			}
		}

		// Try refresh, but gracefully degrade if it fails
		try {
			await this.refresh()
			this.lastRefreshFailure = 0
			logger.info(`[Auth] Token refreshed successfully via ${this.authType}, expires ${this.expiresAt?.toISOString()}`)
		} catch (e) {
			this.lastRefreshFailure = Date.now()
			const msg = e instanceof Error ? e.message : String(e)
			logger.error(`[Auth] Refresh failed (${this.authType}): ${msg}`)
			if (this.accessToken && !this.isActuallyExpired()) {
				logger.warn("[Auth] Using existing token despite refresh failure")
				return this.accessToken
			}
			logger.error(`[Auth] No usable token — refresh failed and token expired`)
			throw new Error("Service temporarily unavailable")
		}

		if (!this.accessToken) {
			throw new Error("Token refresh failed - no access token")
		}
		return this.accessToken
	}
	
	async forceRefresh(): Promise<void> {
		// Re-read from disk first
		if (this.credentialSource) {
			this.reloadCredentials()
			if (this.accessToken && !this.isExpired()) return
		}
		await this.refresh()
	}
	
	private async refresh(): Promise<void> {
		// Mutex: if already refreshing, wait for it
		if (this.refreshing) {
			await this.refreshing
			return
		}
		
		this.refreshing = this.doRefresh()
		try {
			await this.refreshing
		} finally {
			this.refreshing = null
		}
	}
	
	private saveTokenToFile(result: { accessToken: string; refreshToken: string; expiresAt: string }) {
		if (!this.credentialSource) return

		if (this.credentialSource.type === "json") {
			const filePath = resolve(this.credentialSource.path.replace(/^~/, process.env.HOME ?? "~"))
			try {
				const existing = existsSync(filePath) ? JSON.parse(readFileSync(filePath, "utf-8")) : {}
				existing.accessToken = result.accessToken
				existing.refreshToken = result.refreshToken
				existing.expiresAt = result.expiresAt
				writeFileSync(filePath, JSON.stringify(existing, null, 2))
			} catch (e) {
				console.error(`[Auth] Failed to save refreshed token to JSON: ${e instanceof Error ? e.message : e}`)
			}
		} else if (this.credentialSource.type === "sqlite") {
			const dbPath = resolve(this.credentialSource.path.replace(/^~/, process.env.HOME ?? "~"))
			try {
				const { Database } = require("bun:sqlite") as typeof import("bun:sqlite")
				const db = new Database(dbPath)
				const tokenKeys = [
					"kirocli:odic:token",
					"kirocli:social:token",
					"codewhisperer:odic:token",
				]
				// Find the key that exists
				let targetKey = tokenKeys[0]
				for (const key of tokenKeys) {
					const row = db.query("SELECT key FROM auth_kv WHERE key = ?").get(key) as { key: string } | null
					if (row) { targetKey = key; break }
				}
				const row = db.query("SELECT value FROM auth_kv WHERE key = ?").get(targetKey) as { value: string } | null
				const existing = row ? JSON.parse(row.value) : {}
				existing.accessToken = result.accessToken
				existing.access_token = result.accessToken
				existing.refreshToken = result.refreshToken
				existing.refresh_token = result.refreshToken
				existing.expiresAt = result.expiresAt
				existing.expires_at = result.expiresAt
				db.run("INSERT OR REPLACE INTO auth_kv (key, value) VALUES (?, ?)", [targetKey, JSON.stringify(existing)])
				db.close()
			} catch (e) {
				console.error(`[Auth] Failed to save refreshed token to SQLite: ${e instanceof Error ? e.message : e}`)
			}
		}
	}

	private async doRefresh(): Promise<void> {
		if (!this.refreshToken) {
			throw new Error("No refresh token available")
		}

		const region = this.region
		logger.info(`[Auth] Refreshing token via ${this.authType}, region=${region}`)

		if (this.authType === AuthType.AWS_SSO_OIDC) {
			if (!this.clientId || !this.clientSecret) {
				throw new Error("SSO OIDC requires clientId and clientSecret")
			}
			try {
				const result = await refreshSsoOidcToken(
					this.refreshToken,
					this.clientId,
					this.clientSecret,
					region,
					this.scopes ?? undefined,
				)
				this.accessToken = result.accessToken
				this.refreshToken = result.refreshToken
				this.expiresAt = new Date(result.expiresAt)
				this.saveTokenToFile(result)
			} catch (e) {
				// 400 = stale token, kiro-cli may have refreshed — reload and retry once
				const is400 = e instanceof Error && e.message.includes("(400)")
				if (is400 && this.credentialSource?.type === "sqlite") {
					logger.warn("[Auth] SSO OIDC 400 — reloading SQLite and retrying...")
					this.loadFromSqlite(this.credentialSource.path)
					this.detectAuthType()
					if (!this.refreshToken || !this.clientId || !this.clientSecret) {
						throw e
					}
					const result = await refreshSsoOidcToken(
						this.refreshToken,
						this.clientId,
						this.clientSecret,
						region,
						this.scopes ?? undefined,
					)
					this.accessToken = result.accessToken
					this.refreshToken = result.refreshToken
					this.expiresAt = new Date(result.expiresAt)
					this.saveTokenToFile(result)
				} else {
					throw e
				}
			}
		} else {
			const result = await refreshDesktopToken(this.refreshToken, region)
			this.accessToken = result.accessToken
			this.refreshToken = result.refreshToken
			this.expiresAt = new Date(result.expiresAt)
			this.saveTokenToFile(result)
		}
	}
}
