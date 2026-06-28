import { logger } from "../logging/logger"

const KIRO_REFRESH_URL =
	"https://prod.{region}.auth.desktop.kiro.dev/refreshToken"
const AWS_SSO_OIDC_URL = "https://oidc.{region}.amazonaws.com/token"

export interface RefreshResult {
	accessToken: string
	refreshToken: string
	expiresAt: string
}

export async function refreshDesktopToken(
	refreshToken: string,
	region: string,
): Promise<RefreshResult> {
	const url = KIRO_REFRESH_URL.replace("{region}", region)
	logger.debug(`[Auth] Desktop refresh → ${url}`)
	const res = await fetch(url, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({ refreshToken }),
	})

	if (!res.ok) {
		const text = await res.text()
		logger.error(`[Auth] Desktop refresh failed: ${res.status} ${text.slice(0, 300)}`)
		throw new Error(`Desktop token refresh failed (${res.status}): ${text}`)
	}

	const data = (await res.json()) as RefreshResult & Record<string, unknown>
	return {
		accessToken: data.accessToken,
		refreshToken: data.refreshToken ?? refreshToken,
		expiresAt: data.expiresAt,
	}
}

export async function refreshSsoOidcToken(
	refreshToken: string,
	clientId: string,
	clientSecret: string,
	region: string,
	scopes?: string[],
): Promise<RefreshResult> {
	const url = AWS_SSO_OIDC_URL.replace("{region}", region)
	logger.debug(`[Auth] SSO OIDC refresh → ${url}`)

	// AWS SSO OIDC CreateToken API uses JSON with camelCase params
	const payload: Record<string, string | string[]> = {
		grantType: "refresh_token",
		clientId,
		clientSecret,
		refreshToken,
	}
	if (scopes?.length) {
		payload.scope = scopes
	}

	const res = await fetch(url, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify(payload),
	})

	if (!res.ok) {
		const text = await res.text()
		logger.error(`[Auth] SSO OIDC refresh failed: ${res.status} ${text.slice(0, 300)}`)
		throw new Error(
			`SSO OIDC token refresh failed (${res.status}): ${text}`,
		)
	}

	interface OidcResponse {
		accessToken: string
		refreshToken?: string
		expiresIn?: number
	}
	const data = (await res.json()) as OidcResponse
	const expiresIn = data.expiresIn ?? 3600
	const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString()

	return {
		accessToken: data.accessToken,
		refreshToken: data.refreshToken ?? refreshToken,
		expiresAt,
	}
}
