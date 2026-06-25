"use client"

import { useRouter } from "next/navigation"
import { type FormEvent, useState } from "react"
import { apiFetch } from "@/lib/api"

export default function LoginPage() {
	const router = useRouter()
	const [password, setPassword] = useState("")
	const [error, setError] = useState("")
	const [loading, setLoading] = useState(false)

	async function handleSubmit(e: FormEvent) {
		e.preventDefault()
		setError("")
		setLoading(true)
		try {
			const { token } = await apiFetch<{ token: string }>(
				"/admin/login",
				{
					method: "POST",
					body: JSON.stringify({ password }),
				},
			)
			document.cookie = `session=${token}; path=/; max-age=${60 * 60 * 24}; samesite=strict`
			router.push("/")
		} catch {
			setError("Invalid password")
		} finally {
			setLoading(false)
		}
	}

	return (
		<div className="flex-1 flex items-center justify-center p-4">
			<div className="w-full max-w-sm space-y-6">
				<div className="flex flex-col items-center gap-3">
					<div className="size-10 rounded-xl bg-gradient-to-br from-neon-purple to-neon-cyan glow-purple flex items-center justify-center">
						<span className="font-mono text-sm font-bold text-white">
							K
						</span>
					</div>
					<h1 className="font-mono text-lg font-semibold tracking-tight">
						Kiro Gateway
					</h1>
					<p className="font-mono text-[10px] text-muted-foreground">
						Admin Dashboard
					</p>
				</div>

				<form
					onSubmit={handleSubmit}
					className="rounded-xl border border-border/60 bg-card p-6 space-y-4 backdrop-blur-sm"
				>
					<div className="space-y-2">
						<label
							htmlFor="password"
							className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground"
						>
							Password
						</label>
						<input
							id="password"
							type="password"
							value={password}
							onChange={(e) => setPassword(e.target.value)}
							placeholder="Enter admin password"
							className="w-full rounded-lg border border-border/60 bg-background px-3 py-2 font-mono text-xs text-foreground placeholder:text-muted-foreground/50 outline-none focus:border-neon-purple/50"
						/>
					</div>

					{error && (
						<p className="font-mono text-[10px] text-destructive">
							{error}
						</p>
					)}

					<button
						type="submit"
						disabled={loading || !password}
						className="w-full rounded-lg bg-neon-purple/20 py-2 font-mono text-[10px] uppercase tracking-widest text-neon-purple transition-colors hover:bg-neon-purple/30 disabled:opacity-50 cursor-pointer"
					>
						{loading ? "Signing in..." : "Sign in"}
					</button>
				</form>
			</div>
		</div>
	)
}
