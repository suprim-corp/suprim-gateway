import type { KiroAuthManager } from "../auth/manager"
import { env } from "../config"
import { buildKiroHeaders } from "./headers"

const MAX_RETRIES = 3
const BASE_RETRY_DELAY = 1000

export interface KiroRequestOptions {
	method: string
	url: string
	body?: unknown
	stream?: boolean
}

export class KiroHttpClient {
	constructor(private auth: KiroAuthManager) {}

	async request(opts: KiroRequestOptions): Promise<Response> {
		const maxRetries = opts.stream
			? env.FIRST_TOKEN_MAX_RETRIES
			: MAX_RETRIES
		let lastError: Error | null = null
		let lastResponse: Response | null = null

		for (let attempt = 0; attempt < maxRetries; attempt++) {
			try {
				const token = await this.auth.getAccessToken()
				const headers = buildKiroHeaders(this.auth, token)

				const res = await fetch(opts.url, {
					method: opts.method,
					headers,
					body: opts.body ? JSON.stringify(opts.body) : undefined,
				})

				if (res.status === 200) return res

				// 403 — token expired, refresh and retry
				if (res.status === 403) {
					console.warn(
						`[KiroClient] 403, refreshing token (attempt ${attempt + 1}/${maxRetries})`,
					)
					await this.auth.forceRefresh()
					continue
				}

				// 429 — rate limit
				if (res.status === 429) {
					lastResponse = res
					const delay = BASE_RETRY_DELAY * 2 ** attempt
					console.warn(
						`[KiroClient] 429, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
					)
					await sleep(delay)
					continue
				}

				// 5xx — server error
				if (res.status >= 500) {
					lastResponse = res
					const delay = BASE_RETRY_DELAY * 2 ** attempt
					console.warn(
						`[KiroClient] ${res.status}, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
					)
					await sleep(delay)
					continue
				}

				// Other errors — return as-is
				return res
			} catch (e: unknown) {
				lastError = e instanceof Error ? e : new Error(String(e))
				const delay = BASE_RETRY_DELAY * 2 ** attempt
				console.error(
					`[KiroClient] Network error: ${lastError.message}, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
				)
				await sleep(delay)
			}
		}

		// Return last response if we have one (429/5xx exhausted)
		if (lastResponse) return lastResponse

		throw lastError ?? new Error("Request failed after all retries")
	}
}

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms))
}
