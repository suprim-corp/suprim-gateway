"use client"

import { useState } from "react"
import { format, subHours, subDays, subWeeks, subMonths, startOfDay, endOfDay } from "date-fns"
import { CalendarBlank, CaretDown } from "@phosphor-icons/react"
import { AuthGuard } from "@/components/auth-guard"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import {
	Dialog,
	DialogContent,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog"
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useKeys, useModelUsage, useTimeSeries } from "@/hooks/use-admin"
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

interface DateRange {
	from: Date
	to: Date
}

const PRESETS = [
	{ label: "Last 1 hour", hours: 1 },
	{ label: "Last 6 hours", hours: 6 },
	{ label: "Last 24 hours", hours: 24 },
	{ label: "Last 7 days", hours: 168 },
	{ label: "Last 30 days", hours: 720 },
	{ label: "Last 90 days", hours: 2160 },
]

function getHoursFromRange(range: DateRange): number {
	return Math.max(1, Math.round((range.to.getTime() - range.from.getTime()) / 3600_000))
}

function formatRangeLabel(range: DateRange): string {
	const hours = getHoursFromRange(range)
	const preset = PRESETS.find((p) => p.hours === hours)
	if (preset) return preset.label
	return `${format(range.from, "MMM d")} – ${format(range.to, "MMM d")}`
}

function periodKeyFromHours(hours: number): "hour" | "day" | "week" | "month" {
	if (hours <= 1) return "hour"
	if (hours <= 24) return "day"
	if (hours <= 168) return "week"
	return "month"
}

function UsageContent() {
	const now = new Date()
	const [range, setRange] = useState<DateRange>({ from: subHours(now, 24), to: now })
	const [calendarOpen, setCalendarOpen] = useState(false)
	const [calendarRange, setCalendarRange] = useState<{ from?: Date; to?: Date }>({})

	const hours = getHoursFromRange(range)
	const periodKey = periodKeyFromHours(hours)
	const { data: tsData } = useTimeSeries(hours)
	const { data: keysData } = useKeys()
	const { data: modelData } = useModelUsage()

	const chartData = tsData?.data ?? []
	const keys = keysData?.data ?? []
	const models = modelData?.data ?? []

	const totalCost = chartData.reduce((sum, p) => sum + p.cost, 0)
	const totalTokens = chartData.reduce((sum, p) => sum + p.tokens, 0)
	const totalRequests = chartData.reduce((sum, p) => sum + p.requests, 0)

	function handlePreset(hours: number) {
		const to = new Date()
		setRange({ from: subHours(to, hours), to })
	}

	function handleCalendarApply() {
		if (calendarRange.from && calendarRange.to) {
			setRange({ from: startOfDay(calendarRange.from), to: endOfDay(calendarRange.to) })
		} else if (calendarRange.from) {
			setRange({ from: startOfDay(calendarRange.from), to: endOfDay(calendarRange.from) })
		}
		setCalendarOpen(false)
	}

	return (
		<div className="space-y-6">
			<div className="flex items-center justify-between">
				<h1 className="font-mono text-lg font-semibold tracking-tight">Usage</h1>
				<DropdownMenu>
					<DropdownMenuTrigger asChild>
						<Button variant="outline" className="h-9 gap-2 font-mono text-xs cursor-pointer bg-background">
							<CalendarBlank className="size-3.5" />
							{formatRangeLabel(range)}
							<CaretDown className="size-3 opacity-50" />
						</Button>
					</DropdownMenuTrigger>
					<DropdownMenuContent align="end">
						{PRESETS.map((p) => (
							<DropdownMenuItem
								key={p.hours}
								onClick={() => handlePreset(p.hours)}
								className="font-mono text-xs cursor-pointer"
							>
								{p.label}
							</DropdownMenuItem>
						))}
						<DropdownMenuSeparator />
						<DropdownMenuItem
							onClick={() => {
								setCalendarRange({})
								setCalendarOpen(true)
							}}
							className="font-mono text-xs cursor-pointer"
						>
							Custom range...
						</DropdownMenuItem>
					</DropdownMenuContent>
				</DropdownMenu>
			</div>

			<div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
				<Card>
					<CardContent>
						<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
							Cost
						</span>
						<div className="font-mono text-2xl font-bold text-neon-yellow mt-1">
							${totalCost.toFixed(4)}
						</div>
					</CardContent>
				</Card>
				<Card>
					<CardContent>
						<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
							Tokens
						</span>
						<div className="font-mono text-2xl font-bold text-neon-cyan mt-1">
							{totalTokens.toLocaleString()}
						</div>
					</CardContent>
				</Card>
				<Card>
					<CardContent>
						<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
							Requests
						</span>
						<div className="font-mono text-2xl font-bold text-neon-purple mt-1">
							{totalRequests.toLocaleString()}
						</div>
					</CardContent>
				</Card>
			</div>

			<Card>
				<CardContent>
					<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
						Cost Over Time
					</h2>
					<ResponsiveContainer width="100%" height={220}>
						<AreaChart data={chartData}>
							<defs>
								<linearGradient id="costGrad" x1="0" y1="0" x2="0" y2="1">
									<stop offset="0%" stopColor="#facc15" stopOpacity={0.3} />
									<stop offset="100%" stopColor="#facc15" stopOpacity={0} />
								</linearGradient>
							</defs>
							<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} />
							<XAxis dataKey="time" tick={{ fontSize: 10, fill: "#888" }} />
							<YAxis tick={{ fontSize: 10, fill: "#888" }} tickFormatter={(v: number) => `$${v.toFixed(2)}`} />
							<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} formatter={(value) => `$${Number(value).toFixed(4)}`} />
							<Area type="monotone" dataKey="cost" stroke="#facc15" fill="url(#costGrad)" strokeWidth={2} />
						</AreaChart>
					</ResponsiveContainer>
				</CardContent>
			</Card>

			<div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
				<Card>
					<CardContent>
						<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
							Cost by Model
						</h2>
						{models.length === 0 ? (
							<p className="font-mono text-xs text-muted-foreground text-center py-8">No data yet</p>
						) : (
							<div className="space-y-2">
								{models.map((m) => (
									<div key={m.model} className="flex items-center justify-between">
										<span className="font-mono text-[10px] text-muted-foreground truncate flex-1">{m.model}</span>
										<span className="font-mono text-[10px] font-medium text-neon-yellow">${m.cost.toFixed(4)}</span>
									</div>
								))}
							</div>
						)}
					</CardContent>
				</Card>

				<Card>
					<CardContent>
						<h2 className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground mb-4">
							Cost by Key
						</h2>
						{keys.length === 0 ? (
							<p className="font-mono text-xs text-muted-foreground text-center py-8">No data yet</p>
						) : (
							<ResponsiveContainer width="100%" height={180}>
								<BarChart data={keys.filter(k => k.usage[periodKey] > 0)} layout="vertical">
									<CartesianGrid strokeDasharray="3 3" stroke="#333" strokeOpacity={0.5} horizontal={false} />
									<XAxis type="number" tick={{ fontSize: 10, fill: "#888" }} tickFormatter={(v: number) => `$${v.toFixed(2)}`} />
									<YAxis type="category" dataKey="name" tick={{ fontSize: 10, fill: "#888" }} width={80} />
									<Tooltip contentStyle={{ backgroundColor: "#1a1a1a", border: "1px solid #333", borderRadius: 8, fontSize: 11 }} formatter={(value) => `$${Number(value).toFixed(4)}`} />
									<Bar dataKey={`usage.${periodKey}`} fill="#facc15" radius={[0, 4, 4, 0]} opacity={0.8} />
								</BarChart>
							</ResponsiveContainer>
						)}
					</CardContent>
				</Card>
			</div>

			<Dialog open={calendarOpen} onOpenChange={setCalendarOpen}>
				<DialogContent className="sm:max-w-fit!" >
					<DialogHeader>
						<DialogTitle className="font-mono">Select Date Range</DialogTitle>
					</DialogHeader>
					<div className="overflow-x-auto">
						<Calendar
							mode="range"
							selected={calendarRange.from ? { from: calendarRange.from, to: calendarRange.to } : undefined}
							onSelect={(range) => setCalendarRange({ from: range?.from, to: range?.to })}
							numberOfMonths={2}
							disabled={{ after: new Date() }}
						/>
					</div>
					<DialogFooter>
						<Button variant="ghost" onClick={() => setCalendarOpen(false)} className="cursor-pointer">Cancel</Button>
						<Button onClick={handleCalendarApply} disabled={!calendarRange.from} className="cursor-pointer">Apply</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	)
}

export default function UsagePage() {
	return (
		<AuthGuard>
			<UsageContent />
		</AuthGuard>
	)
}
