"use client"

import { Activity, Key, ScrollText, Zap } from "lucide-react"
import {
	Area,
	AreaChart,
	Bar,
	BarChart,
	CartesianGrid,
	ResponsiveContainer,
	Tooltip,
	XAxis,
	YAxis,
} from "recharts"
import { AuthGuard } from "@/components/auth-guard"
import { useStats, useTimeSeries } from "@/hooks/use-admin"

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

function DashboardContent() {
	const { data, isLoading, error } = useStats()
	const { data: tsData } = useTimeSeries(24)

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
					running.
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

	const chartData = tsData?.data ?? []

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

			<div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
				<div className="rounded-xl border border-border/60 bg-card/40 p-4 backdrop-blur-sm">
					<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
						Requests (24h)
					</h2>
					<ResponsiveContainer width="100%" height={200}>
						<AreaChart data={chartData}>
							<defs>
								<linearGradient id="reqGrad" x1="0" y1="0" x2="0" y2="1">
									<stop offset="0%" stopColor="#a78bfa" stopOpacity={0.3} />
									<stop offset="100%" stopColor="#a78bfa" stopOpacity={0} />
								</linearGradient>
							</defs>
							<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} />
							<XAxis dataKey="time" tick={{ fontSize: 10, fill: "#888" }} />
							<YAxis tick={{ fontSize: 10, fill: "#888" }} allowDecimals={false} />
							<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} formatter={(value: number) => value.toLocaleString()} />
							<Area type="monotone" dataKey="requests" stroke="#a78bfa" fill="url(#reqGrad)" strokeWidth={2} />
							<Area type="monotone" dataKey="errors" stroke="#f87171" fill="none" strokeWidth={1.5} strokeDasharray="4 2" />
						</AreaChart>
					</ResponsiveContainer>
				</div>

				<div className="rounded-xl border border-border/60 bg-card/40 p-4 backdrop-blur-sm">
					<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
						Tokens (24h)
					</h2>
					<ResponsiveContainer width="100%" height={200}>
						<BarChart data={chartData}>
							<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} />
							<XAxis dataKey="time" tick={{ fontSize: 10, fill: "#888" }} />
							<YAxis tick={{ fontSize: 10, fill: "#888" }} tickFormatter={(v: number) => v.toLocaleString()} />
							<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} formatter={(value: number) => value.toLocaleString()} />
							<Bar dataKey="tokens" fill="#22d3ee" radius={[4, 4, 0, 0]} opacity={0.8} />
						</BarChart>
					</ResponsiveContainer>
				</div>
			</div>
		</div>
	)
}

export default function DashboardPage() {
	return (
		<AuthGuard>
			<DashboardContent />
		</AuthGuard>
	)
}
