export default function SettingsPage() {
	return (
		<div className="space-y-6">
			<h1 className="font-mono text-lg font-semibold tracking-tight">
				Settings
			</h1>
			<div className="rounded-xl border border-border/60 bg-card/40 p-5 space-y-5 backdrop-blur-sm">
				<div>
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
