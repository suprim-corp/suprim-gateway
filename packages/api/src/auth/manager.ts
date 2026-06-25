import { existsSync, readFileSync } from "node:fs"
import { resolve } from "node:path"
import { env } from "../config"
import { refreshDesktopToken, refreshSsoOidcToken } from "./refresh"
import type { AccountCredentialConfig, KiroCredentials } from "./types"
import { AuthType } from "./types"

const TOKEN_REFRESH_THRESHOLD = 600 // 10 min before expiry

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
			throw new Error(`Credentials file not found: ${resolved}`)
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
	}
	
	private loadFromSqlite(dbPath: string) {
		const resolved = resolve(dbPath.replace(/^~/, process.env.HOME ?? "~"))
		if (!existsSync(resolved)) {
			throw new Error(`SQLite database not found: ${resolved}`)
		}
		
		const { Database } =
			require("bun:sqlite") as typeof import("bun:sqlite")
		const db = new Database(resolved, { readonly: true })
		
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
			refreshToken?: string
			expiresAt?: string
		}
		
		interface RegData {
			clientId?: string
			clientSecret?: string
			scopes?: string[]
		}
		
		let tokenData: TokenData | null = null
		for (const key of tokenKeys) {
			const row = db
				.query("SELECT value FROM auth_kv WHERE key = ?")
				.get(key) as DbRow | null
			if (row?.value) {
				tokenData = JSON.parse(row.value) as TokenData
				break
			}
		}
		
		if (!tokenData) {
			db.close()
			throw new Error("No token found in SQLite database")
		}
		
		this.accessToken = tokenData.accessToken ?? null
		this.refreshToken = tokenData.refreshToken ?? null
		this.expiresAt = tokenData.expiresAt
			? new Date(tokenData.expiresAt)
			: null
		
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
				this.clientId = reg.clientId ?? null
				this.clientSecret = reg.clientSecret ?? null
				this.scopes = reg.scopes ?? null
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
		
		// Re-read credentials from disk — Kiro Desktop may have refreshed them
		if (this.credentialSource) {
			this.reloadCredentials()
			if (this.accessToken && !this.isExpired()) {
				return this.accessToken
			}
		}
		
		// Try refresh, but gracefully degrade if it fails
		try {
			await this.refresh()
		} catch (e) {
			if (this.accessToken && !this.isActuallyExpired()) {
				console.warn(
					"[Auth] Refresh failed, using existing token until expiry:",
					e instanceof Error ? e.message : e,
				)
				return this.accessToken
			}
			throw e
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
	
	private async doRefresh(): Promise<void> {
		if (!this.refreshToken) {
			throw new Error("No refresh token available")
		}
		
		const region = this.region
		
		if (this.authType === AuthType.AWS_SSO_OIDC) {
			if (!this.clientId || !this.clientSecret) {
				throw new Error("SSO OIDC requires clientId and clientSecret")
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
		} else {
			const result = await refreshDesktopToken(this.refreshToken, region)
			this.accessToken = result.accessToken
			this.refreshToken = result.refreshToken
			this.expiresAt = new Date(result.expiresAt)
		}
	}
}
