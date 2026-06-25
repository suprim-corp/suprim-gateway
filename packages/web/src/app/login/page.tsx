"use client"

import { useRouter } from "next/navigation"
import { type FormEvent, useState } from "react"
import Image from "next/image"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { apiFetch } from "@/lib/api"

const LOGO_URL =
	"https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/kiro-color.png"

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
					<Image
						src={LOGO_URL}
						alt="Kiro"
						width={40}
						height={40}
						className="size-10"
						unoptimized
					/>
					<h1 className="font-mono text-lg font-semibold tracking-tight">
						Kiro Gateway
					</h1>
					<p className="font-mono text-[10px] text-muted-foreground">
						Admin Dashboard
					</p>
				</div>

				<Card>
					<CardContent>
						<form onSubmit={handleSubmit} className="space-y-4">
							<div className="space-y-2">
								<label
									htmlFor="password"
									className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground"
								>
									Password
								</label>
								<Input
									id="password"
									type="password"
									value={password}
									onChange={(e) => setPassword(e.target.value)}
									placeholder="Enter admin password"
								/>
							</div>

							{error && (
								<p className="font-mono text-[10px] text-destructive">
									{error}
								</p>
							)}

							<Button
								type="submit"
								variant="default"
								size="lg"
								disabled={loading || !password}
								className="w-full"
							>
								{loading ? "Signing in..." : "Sign in"}
							</Button>
						</form>
					</CardContent>
				</Card>
			</div>
		</div>
	)
}
