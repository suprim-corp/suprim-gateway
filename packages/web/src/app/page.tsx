"use client"

import { Activity, Key, ScrollText, Zap } from "lucide-react"
import useSWR from "swr"
import { apiUrl } from "@/lib/api"

interface Stats {
	totalRequests: number
	totalTokens: number
	activeKeys: number
	errorRate: number
	avgLatencyMs: number
	uptimeSeconds: number
}

const fetcher = (url: string) =>
	fetch(url, {
		headers: {
			Authorization: `Bearer ${process.env.NEXT_PUBLIC_ADMIN_KEY ?? ""}`,
		},
	}).then((r) => {
		if (!r.ok) throw new Error(`${r.status}`)
		return r.json()
	})

function StatCard({
	label,
	value,
	icon: Icon,
	sub,
	color,
}: {
	label: string
	value: string
	icon: React.ElementType
	sub?: string
	color: string
}) {
	return (
		<div className="rounded-xl border border-border/60 bg-card/40 p-4 backdrop-blur-sm">
			<div className="flex items-center justify-between mb-3">
				<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
					{label}
				</span>
				<div
					className={`size-7 rounded-lg ${color} flex items-center justify-center`}
				>
					<Icon className="size-3.5 text-current" />
				</div>
			</div>
			<div className="font-mono text-2xl font-bold tracking-tight">
				{value}
			</div>
			{sub && (
				<p className="mt-1 font-mono text-[10px] text-muted-foreground">
					{sub}
				</p>
			)}
		</div>
	)
}

function formatUptime(seconds: number): string {
	const h = Math.floor(seconds / 3600)
	const m = Math.floor((seconds % 3600) / 60)
	if (h > 0) return `${h}h ${m}m`
	return `${m}m`
}

export default function DashboardPage() {
	const { data, isLoading, error } = useSWR<Stats>(
		apiUrl("/admin/stats"),
		fetcher,
		{ refreshInterval: 5000 },
	)

	if (error) {
		return (
			<div className="flex flex-col items-center justify-center min-h-[60vh] space-y-4">
				<div className="size-12 rounded-xl bg-neon-purple/10 flex items-center justify-center">
					<Activity className="size-6 text-neon-purple" />
				</div>
				<h1 className="font-mono text-lg font-semibold tracking-tight">
					Kiro Gateway
				</h1>
				<p className="font-mono text-xs text-muted-foreground max-w-md text-center leading-relaxed">
					Could not connect to the API. Make sure the backend is
					running and{" "}
					<code className="text-neon-cyan">ADMIN_API_KEY</code> is set
					in <code className="text-neon-cyan">.env</code>.
				</p>
			</div>
		)
	}

	if (isLoading || !data) {
		return (
			<div className="space-y-6">
				<h1 className="font-mono text-lg font-semibold tracking-tight">
					Overview
				</h1>
				<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
					{[1, 2, 3, 4].map((i) => (
						<div
							key={i}
							className="rounded-xl border border-border/60 bg-card/40 p-4 h-28 animate-pulse backdrop-blur-sm"
						/>
					))}
				</div>
			</div>
		)
	}

	return (
		<div className="space-y-6">
			<h1 className="font-mono text-lg font-semibold tracking-tight">
				Overview
			</h1>
			<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
				<StatCard
					label="Requests"
					value={data.totalRequests.toLocaleString()}
					icon={ScrollText}
					color="bg-neon-purple/10 text-neon-purple"
					sub={`${(data.errorRate * 100).toFixed(1)}% error rate`}
				/>
				<StatCard
					label="Tokens"
					value={data.totalTokens.toLocaleString()}
					icon={Zap}
					color="bg-neon-cyan/10 text-neon-cyan"
				/>
				<StatCard
					label="Active Keys"
					value={String(data.activeKeys)}
					icon={Key}
					color="bg-neon-green/10 text-neon-green"
				/>
				<StatCard
					label="Avg Latency"
					value={`${data.avgLatencyMs ?? 0}ms`}
					icon={Activity}
					color="bg-neon-yellow/10 text-neon-yellow"
					sub={`Uptime: ${formatUptime(data.uptimeSeconds ?? 0)}`}
				/>
			</div>
		</div>
	)
}
