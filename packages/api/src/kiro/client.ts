import type { KiroAuthManager } from "../auth"
import { env } from "../config"
import { logger } from "../logging/logger"
import { buildKiroHeaders } from "./headers"

const MAX_RETRIES = 3
const BASE_RETRY_DELAY = 1000

export interface KiroRequestOptions {
	method: string
	url: string
	body?: unknown
	stream?: boolean
}

export interface KiroModelInfo {
	modelId: string
	modelName?: string
	description?: string
	tokenLimits?: { maxInputTokens?: number }
}

export class KiroHttpClient {
	constructor(private auth: KiroAuthManager) {}

	async listModels(): Promise<KiroModelInfo[]> {
		const params = new URLSearchParams({ origin: "AI_EDITOR" })
		if (this.auth.profileArn) {
			params.set("profileArn", this.auth.profileArn)
		}
		const url = `${this.auth.qHost}/ListAvailableModels?${params}`

		logger.info(`Fetching models from ${this.auth.qHost}/ListAvailableModels`)
		const res = await this.request({ method: "GET", url })
		if (res.status !== 200) {
			const text = await res.text()
			throw new Error(
				`ListAvailableModels failed (${res.status}): ${text}`,
			)
		}
		const data = (await res.json()) as { models?: KiroModelInfo[] }
		logger.info(`Fetched ${data.models?.length ?? 0} models`)
		return data.models ?? []
	}

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

				logger.debug(`[Kiro] ${opts.method} ${opts.url} (attempt ${attempt + 1}/${maxRetries})`)

				const res = await fetch(opts.url, {
					method: opts.method,
					headers,
					body: opts.body ? JSON.stringify(opts.body) : undefined,
				})

				if (res.status === 200) return res

				// 403 — token expired, refresh and retry
				if (res.status === 403) {
					const body = await res.text()
					logger.warn(
						`[Kiro] 403 from upstream: ${body.slice(0, 200)}, refreshing token (attempt ${attempt + 1}/${maxRetries})`,
					)
					await this.auth.forceRefresh()
					continue
				}

				// 429 — rate limit
				if (res.status === 429) {
					lastResponse = res
					const delay = BASE_RETRY_DELAY * 2 ** attempt
					logger.warn(
						`[Kiro] 429 rate limited, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
					)
					await sleep(delay)
					continue
				}

				// 5xx — server error
				if (res.status >= 500) {
					lastResponse = res
					const body = await res.clone().text().catch(() => "")
					const delay = BASE_RETRY_DELAY * 2 ** attempt
					logger.warn(
						`[Kiro] ${res.status} from upstream: ${body.slice(0, 200)}, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
					)
					await sleep(delay)
					continue
				}

				// Other errors — log body and return as-is
				const errBody = await res.clone().text().catch(() => "")
				logger.warn(`[Kiro] Unexpected ${res.status} from ${opts.url}: ${errBody.slice(0, 500)}`)
				return res
			} catch (e: unknown) {
				lastError = e instanceof Error ? e : new Error(String(e))
				const delay = BASE_RETRY_DELAY * 2 ** attempt
				logger.error(
					`[Kiro] Network error: ${lastError.message}, waiting ${delay}ms (attempt ${attempt + 1}/${maxRetries})`,
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
