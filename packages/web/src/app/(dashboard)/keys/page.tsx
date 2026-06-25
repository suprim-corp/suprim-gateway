"use client"

import { formatDistanceToNow } from "date-fns"
import { Copy, Plus, Trash2 } from "lucide-react"
import { useState } from "react"
import { AuthGuard } from "@/components/auth-guard"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
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
			<Card>
				<CardContent className="space-y-3">
					<span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
						Create New Key
					</span>
				<div className="flex gap-3">
					<Input
						type="text"
						placeholder="Key name"
						value={newKeyName}
						onChange={(e) => setNewKeyName(e.target.value)}
						className="flex-1"
					/>
					<Input
						type="number"
						value={newKeyRateLimit}
						onChange={(e) =>
							setNewKeyRateLimit(Number(e.target.value))
						}
						className="w-24"
						title="Rate limit/min"
					/>
					<Button
						variant="default"
						size="sm"
						onClick={handleCreate}
						disabled={createKey.isPending || !newKeyName.trim()}
					>
						<Plus className="size-3" />
						Create
					</Button>
				</div>
				{createdKey && (
					<div className="border border-neon-green/30 bg-neon-green/5 p-3 flex items-center justify-between">
						<code className="font-mono text-xs text-neon-green">
							{createdKey}
						</code>
						<Button
							variant="ghost"
							size="icon-xs"
							onClick={() => {
								navigator.clipboard.writeText(createdKey)
							}}
							title="Copy to clipboard"
						>
							<Copy className="size-4" />
						</Button>
					</div>
				)}
				</CardContent>
			</Card>

			{/* Keys table */}
			<Card className="overflow-hidden">
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
									<Button
										variant={key.enabled ? "ghost" : "ghost"}
										size="xs"
										onClick={() =>
											toggleKey.mutate({
												id: key.id,
												enabled: key.enabled,
											})
										}
										className={
											key.enabled
												? "bg-neon-green/10 text-neon-green"
												: "bg-muted/30 text-muted-foreground"
										}
									>
										{key.enabled ? "Active" : "Disabled"}
									</Button>
								</td>
								<td className="px-4 py-2.5">
									<Button
										variant="ghost"
										size="icon-xs"
										onClick={() => deleteKey.mutate(key.id)}
										className="text-muted-foreground hover:text-destructive"
										title="Delete key"
									>
										<Trash2 className="size-3.5" />
									</Button>
								</td>
							</tr>
						))}
					</tbody>
				</table>
			</Card>
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
