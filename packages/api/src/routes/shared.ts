import { FALLBACK_MODELS } from "@kiro-gateway/shared"
import type { AccountCredentialConfig } from "../auth"
import { KiroAuthManager } from "../auth"
import { env } from "../config"
import { KiroHttpClient } from "../kiro"
import { logger } from "../logging/logger"
import { ModelResolver } from "../models"

let authManager: KiroAuthManager | null = null
let httpClient: KiroHttpClient | null = null
export const modelResolver = new ModelResolver()

export const MODEL_CACHE_TTL = 300_000

export function getAuth(): KiroAuthManager {
	if (!authManager) {
		const type: AccountCredentialConfig["type"] = env.KIRO_CLI_DB_FILE
			? "sqlite"
			: env.KIRO_CREDS_FILE
				? "json"
				: "refresh_token"
		const config: AccountCredentialConfig = {
			type,
			path: env.KIRO_CLI_DB_FILE ?? env.KIRO_CREDS_FILE ?? undefined,
			refresh_token: env.REFRESH_TOKEN ?? undefined,
			region: env.KIRO_REGION,
			api_region: env.KIRO_API_REGION,
			profile_arn: env.PROFILE_ARN,
		}
		authManager = new KiroAuthManager(config)
	}
	return authManager
}

export function getClient(): KiroHttpClient {
	if (!httpClient) {
		httpClient = new KiroHttpClient(getAuth())
	}
	return httpClient
}

export async function refreshModelCache() {
	try {
		const client = getClient()
		const auth = getAuth()
		logger.info(`Fetching models from ${auth.qHost}/ListAvailableModels`)
		const models = await client.listModels()
		const ids = models.map((m) => m.modelId)
		modelResolver.setCachedModels(ids)
		const disabled = ids.filter((id) => env.DISABLED_MODELS.includes(id))
		const active = ids.length - disabled.length
		logger.info(
			`Models: ${ids.length} fetched, ${active} active, ${disabled.length} disabled [${ids.join(", ")}]`,
		)
	} catch (e) {
		const fallbackIds = FALLBACK_MODELS.map((m) => m.modelId)
		modelResolver.setCachedModels(fallbackIds)
		logger.warn(
			`Failed to fetch models, using ${fallbackIds.length} fallbacks:`,
			e instanceof Error ? e.message : e,
		)
	}
}
