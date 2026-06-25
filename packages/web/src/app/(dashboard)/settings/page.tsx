"use client"

import { Monitor, Moon, Sun } from "lucide-react"
import { useTheme } from "next-themes"
import { AuthGuard } from "@/components/auth-guard"
import { Button } from "@/components/ui/button"

function SettingsContent() {
	const { theme, setTheme } = useTheme()

	return (
		<div className="space-y-6">
			<h1 className="font-mono text-lg font-semibold tracking-tight">
				Settings
			</h1>
			<div className="rounded-xl border border-border/60 bg-card p-5 space-y-5 backdrop-blur-sm">
				<div>
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						Theme
					</span>
					<div className="mt-2 flex gap-2">
						<Button variant={theme === "light" ? "default" : "outline"} size="sm" onClick={() => setTheme("light")}>
							<Sun className="size-3.5" /> Light
						</Button>
						<Button variant={theme === "dark" ? "default" : "outline"} size="sm" onClick={() => setTheme("dark")}>
							<Moon className="size-3.5" /> Dark
						</Button>
						<Button variant={theme === "system" ? "default" : "outline"} size="sm" onClick={() => setTheme("system")}>
							<Monitor className="size-3.5" /> System
						</Button>
					</div>
				</div>
				<div className="border-t border-border/30 pt-4">
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						API Base URL
					</span>
					<p className="mt-1 font-mono text-xs text-foreground">
						{process.env.NEXT_PUBLIC_API_URL ??
							"http://localhost:3001"}
					</p>
				</div>
				<div className="border-t border-border/30 pt-4">
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						Version
					</span>
					<p className="mt-1 font-mono text-xs text-foreground">
						0.1.0 (MVP)
					</p>
				</div>
				<div className="border-t border-border/30 pt-4">
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						Stack
					</span>
					<p className="mt-1 font-mono text-xs text-foreground">
						Elysia 1.4 + Next.js 16.2 + SQLite + Bun
					</p>
				</div>
			</div>
		</div>
	)
}

export default function SettingsPage() {
	return (
		<AuthGuard>
			<SettingsContent />
		</AuthGuard>
	)
}
