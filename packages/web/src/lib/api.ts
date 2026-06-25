const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:3001"

export async function apiFetch<T>(
	path: string,
	options?: RequestInit,
): Promise<T> {
	const adminKey = process.env.NEXT_PUBLIC_ADMIN_KEY ?? ""
	const res = await fetch(`${API_BASE}${path}`, {
		...options,
		headers: {
			"Content-Type": "application/json",
			Authorization: `Bearer ${adminKey}`,
			...options?.headers,
		},
	})
	if (!res.ok) {
		const err = await res.json().catch(() => ({ error: res.statusText }))
		throw new Error((err as { error?: string }).error ?? res.statusText)
	}
	return res.json() as Promise<T>
}

export function apiUrl(path: string): string {
	return `${API_BASE}${path}`
}
