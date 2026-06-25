"use client"

import { Monitor, Moon, Sun } from "lucide-react"
import { useTheme } from "next-themes"
import { AuthGuard } from "@/components/auth-guard"

function ThemeButton({
	active,
	onClick,
	icon: Icon,
	label,
}: {
	active: boolean
	onClick: () => void
	icon: React.ElementType
	label: string
}) {
	return (
		<button
			type="button"
			onClick={onClick}
			className={`cursor-pointer flex items-center gap-2 rounded-lg border px-3 py-2 font-mono text-xs transition-colors ${
				active
					? "border-primary bg-primary/10 text-foreground"
					: "border-border/60 bg-card text-muted-foreground hover:text-foreground hover:border-border"
			}`}
		>
			<Icon className="size-3.5" />
			{label}
		</button>
	)
}

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
						<ThemeButton active={theme === "light"} onClick={() => setTheme("light")} icon={Sun} label="Light" />
						<ThemeButton active={theme === "dark"} onClick={() => setTheme("dark")} icon={Moon} label="Dark" />
						<ThemeButton active={theme === "system"} onClick={() => setTheme("system")} icon={Monitor} label="System" />
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
