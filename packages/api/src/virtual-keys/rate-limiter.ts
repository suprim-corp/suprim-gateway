const windows = new Map<string, number[]>()

export function isRateLimited(keyId: string, limitPerMin: number): boolean {
	const now = Date.now()
	const windowMs = 60_000
	const timestamps = windows.get(keyId) ?? []

	const valid = timestamps.filter((t) => now - t < windowMs)

	if (valid.length >= limitPerMin) {
		windows.set(keyId, valid)
		return true
	}

	valid.push(now)
	windows.set(keyId, valid)
	return false
}

// ponytail: cleanup stale entries every 5min to avoid memory leak
setInterval(() => {
	const now = Date.now()
	for (const [key, timestamps] of windows) {
		const valid = timestamps.filter((t) => now - t < 60_000)
		if (valid.length === 0) windows.delete(key)
		else windows.set(key, valid)
	}
}, 300_000)
