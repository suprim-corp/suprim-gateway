"use client"

import { Activity } from "lucide-react"
import { AuthGuard } from "@/components/auth-guard"
import { useLogs } from "@/hooks/use-admin"

function StatusBadge({ status }: { status: number }) {
	const color =
		status < 300
			? "text-neon-green"
			: status < 500
				? "text-neon-yellow"
				: "text-destructive"
	return (
		<span className={`font-mono text-[10px] font-medium ${color}`}>
			{status}
		</span>
	)
}

function formatTimestamp(ts: number): string {
	const d = new Date(ts)
	const year = d.getFullYear()
	const month = String(d.getMonth() + 1).padStart(2, "0")
	const day = String(d.getDate()).padStart(2, "0")
	const time = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
	return `${year}-${month}-${day} ${time}`
}

function LogsContent() {
	const { data, isLoading } = useLogs()

	return (
		<div className="space-y-6">
			<div className="flex items-center justify-between">
				<h1 className="font-mono text-lg font-semibold tracking-tight">
					Logs
				</h1>
				{data && (
					<span className="font-mono text-[10px] text-muted-foreground">
						{data.total} total
					</span>
				)}
			</div>

			<div className="rounded-xl border border-border/60 bg-card/40 overflow-hidden backdrop-blur-sm">
				<table className="w-full text-xs">
					<thead>
						<tr className="border-b border-border/40">
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Status
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Model
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								In
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Out
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Latency
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Stream
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Key
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Time
							</th>
						</tr>
					</thead>
					<tbody className="divide-y divide-border/20">
						{isLoading && (
							<tr>
								<td
									colSpan={8}
									className="px-4 py-8 text-center font-mono text-xs text-muted-foreground"
								>
									<div className="flex items-center justify-center gap-2">
										<Activity className="size-3 animate-pulse" />
										Loading...
									</div>
								</td>
							</tr>
						)}
						{data?.data.length === 0 && (
							<tr>
								<td
									colSpan={8}
									className="px-4 py-8 text-center font-mono text-xs text-muted-foreground"
								>
									No requests yet
								</td>
							</tr>
						)}
						{data?.data.map((log) => (
							<tr
								key={log.id}
								className="transition-colors hover:bg-muted/5"
							>
								<td className="px-4 py-2.5">
									<StatusBadge status={log.status} />
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px]">
									{log.model}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{log.promptTokens?.toLocaleString() ?? "—"}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{log.completionTokens?.toLocaleString() ?? "—"}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{log.latencyMs ? `${log.latencyMs}ms` : "—"}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px]">
									<span
										className={
											log.streaming
												? "text-neon-cyan"
												: "text-muted-foreground"
										}
									>
										{log.streaming ? "SSE" : "sync"}
									</span>
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{log.virtualKeyName ?? "—"}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{formatTimestamp(log.createdAt)}
								</td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
		</div>
	)
}

export default function LogsPage() {
	return (
		<AuthGuard>
			<LogsContent />
		</AuthGuard>
	)
}
