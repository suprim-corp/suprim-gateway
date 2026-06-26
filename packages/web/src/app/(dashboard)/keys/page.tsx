"use client"

import { formatDistanceToNow } from "date-fns"
import { Ban, ChevronDown, Copy, Pencil, Plus } from "lucide-react"
import { useState } from "react"
import { AuthGuard } from "@/components/auth-guard"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
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
	DropdownMenuRadioGroup,
	DropdownMenuRadioItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import {
	useCreateKey,
	useKeyBudget,
	useKeys,
	useRevokeKey,
	useToggleKey,
	useUpdateKeyBudget,
} from "@/hooks/use-admin"

const BUDGET_PERIODS = [
	{ value: "", label: "Unlimited" },
	{ value: "hour", label: "Per Hour" },
	{ value: "day", label: "Per Day" },
	{ value: "week", label: "Per Week" },
	{ value: "month", label: "Per Month" },
]

function BudgetBadge({ period, tokens, requests }: { period: string | null; tokens: number | null; requests: number | null }) {
	if (!period) return <span className="text-muted-foreground">—</span>
	const parts: string[] = []
	if (tokens != null) parts.push(`${tokens.toLocaleString()} tok`)
	if (requests != null) parts.push(`${requests.toLocaleString()} req`)
	return (
		<span className="text-neon-cyan">
			{parts.join(" / ")}/{period}
		</span>
	)
}

function BudgetDialog({ keyId, keyName, currentPeriod, currentTokens, currentRequests, open, onClose }: {
	keyId: string
	keyName: string
	currentPeriod: string | null
	currentTokens: number | null
	currentRequests: number | null
	open: boolean
	onClose: () => void
}) {
	const [period, setPeriod] = useState(currentPeriod ?? "")
	const [tokens, setTokens] = useState(currentTokens?.toString() ?? "")
	const [requests, setRequests] = useState(currentRequests?.toString() ?? "")
	const updateBudget = useUpdateKeyBudget()
	const { data: usage } = useKeyBudget(period ? keyId : null)

	function handleSave() {
		updateBudget.mutate(
			{
				id: keyId,
				budgetPeriod: period || null,
				budgetTokens: tokens ? Number(tokens) : null,
				budgetRequests: requests ? Number(requests) : null,
			},
			{ onSuccess: onClose },
		)
	}

	return (
		<Dialog open={open} onOpenChange={(v) => { if (!v) onClose() }}>
			<DialogContent className="sm:max-w-md">
				<DialogHeader>
					<DialogTitle className="font-mono">Budget — {keyName}</DialogTitle>
				</DialogHeader>
				<div className="space-y-4">
					<div className="space-y-2">
						<label className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Period</label>
						<DropdownMenu>
							<DropdownMenuTrigger asChild>
								<Button variant="outline" className="h-9 w-full justify-between font-mono text-xs cursor-pointer bg-background">
									{BUDGET_PERIODS.find((p) => p.value === period)?.label ?? "Unlimited"}
									<ChevronDown className="size-3 opacity-50" />
								</Button>
							</DropdownMenuTrigger>
							<DropdownMenuContent>
								<DropdownMenuRadioGroup value={period} onValueChange={setPeriod}>
									{BUDGET_PERIODS.map((p) => (
										<DropdownMenuRadioItem key={p.value} value={p.value} className="font-mono text-xs cursor-pointer">
											{p.label}
										</DropdownMenuRadioItem>
									))}
								</DropdownMenuRadioGroup>
							</DropdownMenuContent>
						</DropdownMenu>
					</div>
					<div className="grid grid-cols-2 gap-4">
						<div className="space-y-2">
							<label className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Token Limit</label>
							<Input
								type="number"
								placeholder="Unlimited"
								value={tokens}
								onChange={(e) => setTokens(e.target.value)}
								className="h-9"
								disabled={!period}
							/>
						</div>
						<div className="space-y-2">
							<label className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Request Limit</label>
							<Input
								type="number"
								placeholder="Unlimited"
								value={requests}
								onChange={(e) => setRequests(e.target.value)}
								className="h-9"
								disabled={!period}
							/>
						</div>
					</div>
					{usage && usage.budgetPeriod && (
						<div className="border border-border/40 p-3 space-y-1">
							<span className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">Current Usage</span>
							<div className="flex justify-between font-mono text-xs">
								<span>Tokens</span>
								<span>{usage.tokens.used.toLocaleString()}{usage.tokens.limit != null && <span className="text-muted-foreground"> / {usage.tokens.limit.toLocaleString()}</span>}</span>
							</div>
							<div className="flex justify-between font-mono text-xs">
								<span>Requests</span>
								<span>{usage.requests.used.toLocaleString()}{usage.requests.limit != null && <span className="text-muted-foreground"> / {usage.requests.limit.toLocaleString()}</span>}</span>
							</div>
						</div>
					)}
				</div>
				<DialogFooter>
					<Button variant="ghost" onClick={onClose} className="cursor-pointer">Cancel</Button>
					<Button onClick={handleSave} disabled={updateBudget.isPending} className="cursor-pointer">Save</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	)
}

function KeysContent() {
	const { data, isLoading } = useKeys()
	const createKey = useCreateKey()
	const toggleKey = useToggleKey()
	const revokeKey = useRevokeKey()
	const [newKeyName, setNewKeyName] = useState("")
	const [newKeyRateLimit, setNewKeyRateLimit] = useState(60)
	const [createdKey, setCreatedKey] = useState<string | null>(null)
	const [editingBudget, setEditingBudget] = useState<string | null>(null)

	const editingKey = data?.data.find((k) => k.id === editingBudget)

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
						className="cursor-pointer"
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
							className="cursor-pointer"
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
								Budget
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
									colSpan={9}
									className="px-4 py-8 text-center font-mono text-xs text-muted-foreground"
								>
									Loading...
								</td>
							</tr>
						)}
						{data?.data.length === 0 && (
							<tr>
								<td
									colSpan={9}
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
									<BudgetBadge period={key.budgetPeriod} tokens={key.budgetTokens} requests={key.budgetRequests} />
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
									{key.revokedAt ? (
										<span className="font-mono text-[10px] bg-destructive/10 text-destructive px-2 py-0.5 rounded">
											Revoked
										</span>
									) : (
										<Button
											variant="ghost"
											size="xs"
											onClick={() =>
												toggleKey.mutate({
													id: key.id,
													enabled: key.enabled,
												})
											}
											className={`cursor-pointer ${
												key.enabled
													? "bg-neon-green/10 text-neon-green"
													: "bg-muted/30 text-muted-foreground"
											}`}
										>
											{key.enabled ? "Active" : "Disabled"}
										</Button>
									)}
								</td>
								<td className="px-4 py-2.5 flex gap-1">
									<Button
										variant="ghost"
										size="icon-xs"
										onClick={() => setEditingBudget(key.id)}
										className="text-muted-foreground hover:text-neon-cyan cursor-pointer"
										title="Edit budget"
										disabled={!!key.revokedAt}
									>
										<Pencil className="size-3.5" />
									</Button>
									{!key.revokedAt && (
										<Button
											variant="ghost"
											size="icon-xs"
											onClick={() => {
												if (confirm("Revoke this key? This cannot be undone.")) {
													revokeKey.mutate(key.id)
												}
											}}
											className="text-muted-foreground hover:text-destructive cursor-pointer"
											title="Revoke key"
										>
											<Ban className="size-3.5" />
										</Button>
									)}
								</td>
							</tr>
						))}
					</tbody>
				</table>
			</Card>

			{editingKey && (
				<BudgetDialog
					keyId={editingKey.id}
					keyName={editingKey.name}
					currentPeriod={editingKey.budgetPeriod}
					currentTokens={editingKey.budgetTokens}
					currentRequests={editingKey.budgetRequests}
					open={!!editingBudget}
					onClose={() => setEditingBudget(null)}
				/>
			)}
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
