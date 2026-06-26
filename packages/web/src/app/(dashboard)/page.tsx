"use client"

import { Activity, DollarSign, Key, ScrollText, Zap } from "lucide-react"
import {
	Area,
	AreaChart,
	Bar,
	BarChart,
	CartesianGrid,
	Cell,
	Pie,
	PieChart,
	ResponsiveContainer,
	Tooltip,
	XAxis,
	YAxis,
} from "recharts"
import { AuthGuard } from "@/components/auth-guard"
import { Card, CardContent } from "@/components/ui/card"
import { useModelUsage, useStats, useTimeSeries, useTopKeys } from "@/hooks/use-admin"

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
		<Card>
			<CardContent>
				<div className="flex items-center justify-between mb-3">
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						{label}
					</span>
					<div
						className={`size-7 ${color} flex items-center justify-center`}
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
			</CardContent>
		</Card>
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
	const { data: modelData } = useModelUsage()
	const { data: keyData } = useTopKeys()

	if (error) {
		return (
			<div className="flex flex-col items-center justify-center min-h-[60vh] space-y-4">
				<div className="size-12 bg-neon-purple/10 flex items-center justify-center">
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
						<Card key={i} className="h-28 animate-pulse" />
					))}
				</div>
			</div>
		)
	}

	const chartData = tsData?.data ?? []
	const models = modelData?.data ?? []
	const topKeys = keyData?.data ?? []
	const PIE_COLORS = ["#a78bfa", "#22d3ee", "#4ade80", "#facc15", "#f87171", "#fb923c", "#a3e635", "#e879f9"]

	return (
		<div className="space-y-6">
			<h1 className="font-mono text-lg font-semibold tracking-tight">
				Overview
			</h1>
			<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
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
					label="Cost"
					value={`$${data.totalCost.toFixed(4)}`}
					icon={DollarSign}
					color="bg-neon-yellow/10 text-neon-yellow"
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
					color="bg-neon-purple/10 text-neon-purple"
					sub={`Uptime: ${formatUptime(data.uptimeSeconds ?? 0)}`}
				/>
			</div>

			<div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
				<Card>
					<CardContent>
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
								<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} formatter={(value) => Number(value).toLocaleString()} />
								<Area type="monotone" dataKey="requests" stroke="#a78bfa" fill="url(#reqGrad)" strokeWidth={2} />
								<Area type="monotone" dataKey="errors" stroke="#f87171" fill="none" strokeWidth={1.5} strokeDasharray="4 2" />
							</AreaChart>
						</ResponsiveContainer>
					</CardContent>
				</Card>

				<Card>
					<CardContent>
						<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
							Tokens (24h)
						</h2>
						<ResponsiveContainer width="100%" height={200}>
							<BarChart data={chartData}>
								<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} />
								<XAxis dataKey="time" tick={{ fontSize: 10, fill: "#888" }} />
								<YAxis tick={{ fontSize: 10, fill: "#888" }} tickFormatter={(v: number) => v.toLocaleString()} />
								<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} formatter={(value) => Number(value).toLocaleString()} />
								<Bar dataKey="tokens" fill="#22d3ee" radius={[4, 4, 0, 0]} opacity={0.8} />
							</BarChart>
						</ResponsiveContainer>
					</CardContent>
				</Card>
			</div>

			<div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
				<Card>
					<CardContent>
						<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
							Models by Requests
						</h2>
						{models.length === 0 ? (
							<p className="font-mono text-xs text-muted-foreground text-center py-8">No data yet</p>
						) : (
							<div className="flex items-center gap-4">
								<ResponsiveContainer width="50%" height={180}>
									<PieChart>
										<Pie data={models} dataKey="requests" nameKey="model" cx="50%" cy="50%" outerRadius={70} strokeWidth={1} stroke="#1a1a1a">
											{models.map((_, i) => (
												<Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
											))}
										</Pie>
										<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} />
									</PieChart>
								</ResponsiveContainer>
								<div className="flex-1 space-y-1.5">
									{models.map((m, i) => (
										<div key={m.model} className="flex items-center gap-2">
											<div className="size-2.5" style={{ backgroundColor: PIE_COLORS[i % PIE_COLORS.length] }} />
											<span className="font-mono text-[10px] text-muted-foreground truncate flex-1">{m.model}</span>
											<span className="font-mono text-[10px] font-medium">${m.cost.toFixed(4)}</span>
										</div>
									))}
								</div>
							</div>
						)}
					</CardContent>
				</Card>

				<Card>
					<CardContent>
						<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
							Top Keys
						</h2>
						{topKeys.length === 0 ? (
							<p className="font-mono text-xs text-muted-foreground text-center py-8">No data yet</p>
						) : (
							<ResponsiveContainer width="100%" height={180}>
								<BarChart data={topKeys} layout="vertical">
									<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} horizontal={false} />
									<XAxis type="number" tick={{ fontSize: 10, fill: "#888" }} tickFormatter={(v: number) => v.toLocaleString()} />
									<YAxis type="category" dataKey="name" tick={{ fontSize: 10, fill: "#888" }} width={80} />
									<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} labelStyle={{ color: "#eee" }} formatter={(value) => Number(value).toLocaleString()} />
									<Bar dataKey="requests" fill="#4ade80" radius={[0, 4, 4, 0]} opacity={0.8} />
								</BarChart>
							</ResponsiveContainer>
						)}
					</CardContent>
				</Card>
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
