export type AccountType = "json" | "sqlite" | "refresh_token"
export type AccountStatus = "healthy" | "degraded" | "broken" | "unknown"

export interface Account {
	id: string
	type: AccountType
	path: string | null
	region: string
	apiRegion: string | null
	enabled: boolean
	status: AccountStatus
	lastUsedAt: number | null
	failureCount: number
	createdAt: number
}

export interface VirtualKey {
	id: string
	name: string
	keyPrefix: string
	accountId: string | null
	enabled: boolean
	rateLimitPerMin: number
	allowedModels: string[] | null
	totalRequests: number
	totalTokens: number
	lastUsedAt: number | null
	createdAt: number
}

export interface RequestLog {
	id: string
	virtualKeyId: string | null
	accountId: string | null
	model: string
	requestedModel: string | null
	status: number
	promptTokens: number | null
	completionTokens: number | null
	totalTokens: number | null
	latencyMs: number | null
	firstTokenMs: number | null
	streaming: boolean
	errorMessage: string | null
	createdAt: number
}

export interface DashboardStats {
	totalRequests: number
	totalTokens: number
	activeAccounts: number
	activeKeys: number
	errorRate: number
	uptimeSeconds: number
}

export interface CreateVirtualKeyRequest {
	name: string
	accountId?: string | null
	rateLimitPerMin?: number
	allowedModels?: string[] | null
}

export interface UpdateVirtualKeyRequest {
	name?: string
	enabled?: boolean
	rateLimitPerMin?: number
	allowedModels?: string[] | null
}
