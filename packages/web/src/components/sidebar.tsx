"use client"

import { Key, LayoutDashboard, ScrollText, Settings } from "lucide-react"
import Link from "next/link"
import { usePathname } from "next/navigation"

const nav = [
	{
		label: "Gateway",
		items: [
			{ href: "/", label: "Overview", icon: LayoutDashboard },
			{ href: "/logs", label: "Logs", icon: ScrollText },
			{ href: "/keys", label: "API Keys", icon: Key },
		],
	},
	{
		label: "System",
		items: [{ href: "/settings", label: "Settings", icon: Settings }],
	},
]

export function Sidebar() {
	const pathname = usePathname()

	return (
		<aside className="fixed left-0 top-0 h-full w-56 border-r border-border/50 bg-background/50 backdrop-blur-xl flex flex-col">
			<div className="p-4 border-b border-border/50 flex items-center gap-2.5">
				<div className="size-7 rounded-lg bg-gradient-to-br from-neon-purple to-neon-cyan glow-purple flex items-center justify-center">
					<span className="font-mono text-[10px] font-bold text-white">
						K
					</span>
				</div>
				<span className="font-mono text-sm font-semibold tracking-tight">
					Kiro Gateway
				</span>
			</div>
			<nav className="flex-1 p-3 space-y-4">
				{nav.map((group) => (
					<div key={group.label} className="space-y-1">
						<span className="px-3 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
							{group.label}
						</span>
						{group.items.map(({ href, label, icon: Icon }) => {
							const active =
								href === "/"
									? pathname === "/"
									: pathname.startsWith(href)
							return (
								<Link
									key={href}
									href={href}
									className={`flex items-center gap-3 px-3 py-2 rounded-lg font-mono text-xs transition-colors ${
										active
											? "bg-neon-purple/10 text-neon-purple"
											: "text-muted-foreground hover:text-foreground hover:bg-muted/30"
									}`}
								>
									<Icon className="size-4" />
									{label}
								</Link>
							)
						})}
					</div>
				))}
			</nav>
			<div className="p-4 border-t border-border/50 font-mono text-[10px] text-muted-foreground">
				v0.1.0
			</div>
		</aside>
	)
}
