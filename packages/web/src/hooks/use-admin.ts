import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { apiFetch } from "@/lib/api"

interface Stats {
	totalRequests: number
	totalTokens: number
	totalCost: number
	activeKeys: number
	errorRate: number
	avgLatencyMs: number
	uptimeSeconds: number
}

interface LogEntry {
	id: string
	virtualKeyId: string | null
	virtualKeyName: string | null
	model: string
	requestedModel: string | null
	status: number
	promptTokens: number | null
	completionTokens: number | null
	totalTokens: number | null
	cost: number | null
	latencyMs: number | null
	streaming: boolean | null
	clientIp: string | null
	errorMessage: string | null
	createdAt: number
}

interface LogsResponse {
	data: LogEntry[]
	total: number
}

interface VirtualKey {
	id: string
	name: string
	keyPrefix: string
	enabled: boolean
	revokedAt: number | null
	rateLimitPerMin: number
	allowedModels: string[] | null
	budgetPeriod: string | null
	budgetTokens: number | null
	budgetRequests: number | null
	usage: { hour: number; day: number; week: number; month: number }
	totalRequests: number
	totalTokens: number
	lastUsedAt: number | null
	createdAt: number
}

interface KeysResponse {
	data: VirtualKey[]
}

export function useStats() {
	return useQuery<Stats>({
		queryKey: ["stats"],
		queryFn: () => apiFetch("/admin/stats"),
		refetchInterval: 5000,
	})
}

export function useLogs() {
	return useQuery<LogsResponse>({
		queryKey: ["logs"],
		queryFn: () => apiFetch("/admin/logs?limit=100"),
		refetchInterval: 3000,
	})
}

export function useKeys() {
	return useQuery<KeysResponse>({
		queryKey: ["keys"],
		queryFn: () => apiFetch("/admin/keys"),
		refetchInterval: 5000,
	})
}

export function useCreateKey() {
	const queryClient = useQueryClient()
	return useMutation({
		mutationFn: (input: {
			name: string
			rateLimitPerMin: number
			budgetPeriod?: string | null
			budgetTokens?: number | null
			budgetRequests?: number | null
		}) =>
			apiFetch<{ key: string }>("/admin/keys", {
				method: "POST",
				body: JSON.stringify(input),
			}),
		onSuccess: () => queryClient.invalidateQueries({ queryKey: ["keys"] }),
	})
}


export function useToggleKey() {
	const queryClient = useQueryClient()
	return useMutation({
		mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
			apiFetch(`/admin/keys/${id}`, {
				method: "PATCH",
				body: JSON.stringify({ enabled: !enabled }),
			}),
		onSuccess: () => queryClient.invalidateQueries({ queryKey: ["keys"] }),
	})
}

export function useRevokeKey() {
	const queryClient = useQueryClient()
	return useMutation({
		mutationFn: (id: string) =>
			apiFetch(`/admin/keys/${id}/revoke`, { method: "POST" }),
		onSuccess: () => queryClient.invalidateQueries({ queryKey: ["keys"] }),
	})
}

export function useUpdateKeyBudget() {
	const queryClient = useQueryClient()
	return useMutation({
		mutationFn: ({
			id,
			budgetPeriod,
			budgetTokens,
			budgetRequests,
		}: {
			id: string
			budgetPeriod: string | null
			budgetTokens: number | null
			budgetRequests: number | null
		}) =>
			apiFetch(`/admin/keys/${id}`, {
				method: "PATCH",
				body: JSON.stringify({ budgetPeriod, budgetTokens, budgetRequests }),
			}),
		onSuccess: () => queryClient.invalidateQueries({ queryKey: ["keys"] }),
	})
}

interface BudgetUsageResponse {
	budgetPeriod: string | null
	tokens: { used: number; limit: number | null }
	requests: { used: number; limit: number | null }
}

export function useKeyBudget(keyId: string | null) {
	return useQuery<BudgetUsageResponse>({
		queryKey: ["key-budget", keyId],
		queryFn: () => apiFetch(`/admin/keys/${keyId}/budget`),
		enabled: !!keyId,
		refetchInterval: 5000,
	})
}

interface TimeSeriesPoint {
	time: string
	requests: number
	tokens: number
	errors: number
	cost: number
}

interface TimeSeriesResponse {
	data: TimeSeriesPoint[]
}

export function useTimeSeries(hours = 24) {
	return useQuery<TimeSeriesResponse>({
		queryKey: ["timeseries", hours],
		queryFn: () => apiFetch(`/admin/stats/timeseries?hours=${hours}`),
		refetchInterval: 10000,
	})
}

interface ModelUsage {
	model: string
	requests: number
	tokens: number
	cost: number
}

interface ModelUsageResponse {
	data: ModelUsage[]
}

interface KeyUsage {
	name: string
	requests: number
	tokens: number
}

interface KeyUsageResponse {
	data: KeyUsage[]
}

export function useModelUsage() {
	return useQuery<ModelUsageResponse>({
		queryKey: ["model-usage"],
		queryFn: () => apiFetch("/admin/stats/models"),
		refetchInterval: 10000,
	})
}

export function useTopKeys() {
	return useQuery<KeyUsageResponse>({
		queryKey: ["top-keys"],
		queryFn: () => apiFetch("/admin/stats/top-keys"),
		refetchInterval: 10000,
	})
}

export type { BudgetUsageResponse, KeysResponse, KeyUsage, LogEntry, LogsResponse, ModelUsage, Stats, TimeSeriesPoint, VirtualKey }
