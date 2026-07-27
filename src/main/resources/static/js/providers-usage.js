// Shared usage/quota helpers. Loaded before the files that render usage, since
// both the models dialog and the account cards call summarizeUsage().

// Upstream sends an ISO timestamp; show it in the viewer's own timezone.
// Falls back to the raw value if it is not parseable.
function formatResetTime(value) {
    const date = new Date(value)
    if (isNaN(date.getTime())) return value
    return date.toLocaleString(undefined, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    })
}

function quotaColor(remaining) {
    if (remaining > 50) return 'bg-green-400'
    return remaining > 20 ? 'bg-yellow-400' : 'bg-red-400'
}

// Each provider reports usage in its own shape. Reduce them all to
// {label, detail, percent} so the dialog banner and the account cards can share
// one renderer. percent is always REMAINING, or null when the provider gives no
// figure to draw a bar from. Returns null when there is nothing to show at all.
function summarizeUsage(data) {
    if (!data || data.error) return null

    // Antigravity: {tier, quota, resetTime, buckets} — quota mirrors the most
    // constrained bucket, since that is what will actually block a request.
    if (data.tier || data.quota !== undefined) {
        const remaining = data.quota ?? null
        const detail = []
        if (remaining !== null) detail.push(remaining + '% remaining')
        if (data.resetTime) detail.push('resets ' + formatResetTime(data.resetTime))
        return {
            label: data.tier || 'Antigravity',
            detail: detail.join(' · '),
            percent: remaining
        }
    }

    // Codex: {plan, session, weekly, limitReached, resetCredits} — percentages are
    // USED, and the tighter of the two windows governs.
    if (data.session || data.weekly) {
        const sessionPct = data.session?.usedPercent ?? 0
        const weeklyPct = data.weekly?.usedPercent ?? 0
        const remaining = Math.max(0, 100 - Math.max(sessionPct, weeklyPct))
        const planMap = {
            free: 'Free', go: 'Go', plus: 'Plus',
            pro: 'Pro', business: 'Business', enterprise: 'Enterprise'
        }
        const detail = ['Session ' + sessionPct + '% · Weekly ' + weeklyPct + '%']
        if (data.resetCredits > 0) detail.push(data.resetCredits + ' reset credits')
        return {
            label: 'ChatGPT ' + (planMap[data.plan] || data.plan || 'Codex') +
                (data.limitReached ? ' — LIMIT REACHED' : ''),
            detail: detail.join(' · '),
            percent: remaining
        }
    }

    // Kiro: {usageBreakdownList, subscriptionInfo} — absolute credits, not percent.
    const breakdown = data.usageBreakdownList?.[0]
    if (!breakdown) return null
    const used = breakdown.currentUsageWithPrecision ?? breakdown.currentUsage ?? 0
    const limit = breakdown.usageLimit || 0
    const sub = data.subscriptionInfo
    const planName = (sub && (sub.subscriptionTitle || sub.subscriptionName || sub.subscriptionType)) || ''
    return {
        label: planName ? planName + ' — Credits' : 'Credits',
        detail: used.toFixed(2) + ' / ' + limit.toFixed(0) + ' used',
        percent: limit > 0 ? Math.round(((limit - used) / limit) * 100) : 0
    }
}
