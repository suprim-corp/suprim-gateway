"use client"

import { formatDistanceToNow } from "date-fns"
import { Copy, Plus, Trash2 } from "lucide-react"
import { useState } from "react"
import { AuthGuard } from "@/components/auth-guard"
import {
	useCreateKey,
	useDeleteKey,
	useKeys,
	useToggleKey,
} from "@/hooks/use-admin"

function KeysContent() {
	const { data, isLoading } = useKeys()
	const createKey = useCreateKey()
	const deleteKey = useDeleteKey()
	const toggleKey = useToggleKey()
	const [newKeyName, setNewKeyName] = useState("")
	const [newKeyRateLimit, setNewKeyRateLimit] = useState(60)
	const [createdKey, setCreatedKey] = useState<string | null>(null)

	function handleCreate() {
		if (!newKeyName.trim()) return
		createKey.mutate(
			{ name: newKeyName, rateLimitPerMin: newKeyRateLimit },
			{
				onSuccess: (result) => {
					setCreatedKey(result.key)
					setNewKeyName("")
				},
			},
		)
	}

	return (
		<div className="space-y-6">
			<h1 className="font-mono text-lg font-semibold tracking-tight">
				API Keys
			</h1>

			{/* Create key form */}
			<div className="rounded-xl border border-border/60 bg-card p-4 space-y-3 backdrop-blur-sm">
				<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
					Create New Key
				</span>
				<div className="flex gap-3">
					<input
						type="text"
						placeholder="Key name"
						value={newKeyName}
						onChange={(e) => setNewKeyName(e.target.value)}
						className="flex-1 rounded-lg border border-border/60 bg-background px-3 py-1.5 font-mono text-xs text-foreground placeholder:text-muted-foreground/50 outline-none focus:border-neon-purple/50"
					/>
					<input
						type="number"
						value={newKeyRateLimit}
						onChange={(e) =>
							setNewKeyRateLimit(Number(e.target.value))
						}
						className="w-24 rounded-lg border border-border/60 bg-background px-3 py-1.5 font-mono text-xs text-foreground outline-none focus:border-neon-purple/50"
						title="Rate limit/min"
					/>
					<button
						type="button"
						onClick={handleCreate}
						disabled={createKey.isPending || !newKeyName.trim()}
						className="flex items-center gap-1.5 rounded-lg bg-neon-purple/20 px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest text-neon-purple transition-colors hover:bg-neon-purple/30 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
					>
						<Plus className="size-3" />
						Create
					</button>
				</div>
				{createdKey && (
					<div className="rounded-lg border border-neon-green/30 bg-neon-green/5 p-3 flex items-center justify-between">
						<code className="font-mono text-xs text-neon-green">
							{createdKey}
						</code>
						<button
							type="button"
							onClick={() => {
								navigator.clipboard.writeText(createdKey)
							}}
							className="text-muted-foreground hover:text-foreground cursor-pointer"
							title="Copy to clipboard"
						>
							<Copy className="size-4" />
						</button>
					</div>
				)}
			</div>

			{/* Keys table */}
			<div className="rounded-xl border border-border/60 bg-card overflow-hidden backdrop-blur-sm">
				<table className="w-full text-xs">
					<thead>
						<tr className="border-b border-border/40">
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Name
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Prefix
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Rate
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Requests
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Tokens
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Last Used
							</th>
							<th className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-widest text-muted-foreground font-normal">
								Status
							</th>
							<th className="px-4 py-3"></th>
						</tr>
					</thead>
					<tbody className="divide-y divide-border/20">
						{isLoading && (
							<tr>
								<td
									colSpan={8}
									className="px-4 py-8 text-center font-mono text-xs text-muted-foreground"
								>
									Loading...
								</td>
							</tr>
						)}
						{data?.data.length === 0 && (
							<tr>
								<td
									colSpan={8}
									className="px-4 py-8 text-center font-mono text-xs text-muted-foreground"
								>
									No keys yet. Create one above.
								</td>
							</tr>
						)}
						{data?.data.map((key) => (
							<tr
								key={key.id}
								className="transition-colors hover:bg-muted/5"
							>
								<td className="px-4 py-2.5 font-mono text-xs font-medium">
									{key.name}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{key.keyPrefix}...
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{key.rateLimitPerMin}/min
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px]">
									{key.totalRequests.toLocaleString()}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px]">
									{key.totalTokens.toLocaleString()}
								</td>
								<td className="px-4 py-2.5 font-mono text-[10px] text-muted-foreground">
									{key.lastUsedAt
										? formatDistanceToNow(key.lastUsedAt, {
												addSuffix: true,
											})
										: "Never"}
								</td>
								<td className="px-4 py-2.5">
									<button
										type="button"
										onClick={() =>
											toggleKey.mutate({
												id: key.id,
												enabled: key.enabled,
											})
										}
										className={`font-mono text-[10px] px-2 py-0.5 rounded-md cursor-pointer ${
											key.enabled
												? "bg-neon-green/10 text-neon-green"
												: "bg-muted/30 text-muted-foreground"
										}`}
									>
										{key.enabled ? "Active" : "Disabled"}
									</button>
								</td>
								<td className="px-4 py-2.5">
									<button
										type="button"
										onClick={() => deleteKey.mutate(key.id)}
										className="text-muted-foreground hover:text-destructive transition-colors cursor-pointer"
										title="Delete key"
									>
										<Trash2 className="size-3.5" />
									</button>
								</td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
		</div>
	)
}

export default function KeysPage() {
	return (
		<AuthGuard>
			<KeysContent />
		</AuthGuard>
	)
}
