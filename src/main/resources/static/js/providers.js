function showModels(index) {
    const dialog = document.getElementById('modelsDialog')
    const loading = document.getElementById('modelsLoading')
    const list = document.getElementById('modelsList')
    const error = document.getElementById('modelsError')
    const empty = document.getElementById('modelsEmpty')
    const usageBanner = document.getElementById('usageBanner')

    loading.classList.remove('hidden')
    list.classList.add('hidden')
    error.classList.add('hidden')
    empty.classList.add('hidden')
    usageBanner.classList.add('hidden')
    document.getElementById('usageBar').parentElement.classList.remove('hidden')
    document.getElementById('usageBuckets').classList.add('hidden')
    document.getElementById('usageBuckets').innerHTML = ''
    list.innerHTML = ''
    dialog.showModal()

    fetch('/providers/' + index + '/usage')
        .then(res => res.ok ? res.json() : null)
        .then(data => {
            const usage = summarizeUsage(data)
            if (!usage) return

            document.getElementById('usageLabel').textContent = usage.label
            document.getElementById('usageText').textContent = usage.detail
            const bar = document.getElementById('usageBar')
            if (usage.percent === null) {
                bar.parentElement.classList.add('hidden')
            } else {
                bar.parentElement.classList.remove('hidden')
                bar.style.width = usage.percent + '%'
                bar.className = 'h-full rounded-full transition-all ' + quotaColor(usage.percent)
            }
            renderQuotaBuckets(data.buckets)
            usageBanner.classList.remove('hidden')
        })
        .catch(() => {})

    fetch('/providers/' + index + '/models')
        .then(res => {
            if (!res.ok) throw new Error('Failed to fetch models')
            return res.json()
        })
        .then(models => {
            loading.classList.add('hidden')
            if (models.error) {
                error.textContent = models.error
                error.classList.remove('hidden')
                return
            }
            if (models.length === 0) {
                empty.classList.remove('hidden')
                return
            }
            models.sort((a, b) => (a.id || a).localeCompare(b.id || b))
            models.forEach(m => {
                const li = document.createElement('li')
                li.className = 'flex items-center justify-between px-3 py-1.5 text-xs bg-zinc-950 border border-zinc-800 rounded'
                const name = document.createElement('span')
                name.className = 'text-zinc-300'
                name.textContent = m.id || m
                li.appendChild(name)
                if (m.quota !== undefined && m.quota !== null) {
                    const badge = document.createElement('span')
                    const pct = m.quota
                    badge.textContent = pct + '%'
                    badge.className = 'text-[10px] font-medium px-1.5 py-0.5 rounded ' +
                        (pct > 50 ? 'text-green-400 bg-green-400/10' : pct > 20 ? 'text-yellow-400 bg-yellow-400/10' : 'text-red-400 bg-red-400/10')
                    li.appendChild(badge)
                } else if (m.cost !== undefined && m.cost !== null) {
                    const badge = document.createElement('span')
                    badge.textContent = m.cost + ' ' + (m.unit || '').toLowerCase()
                    badge.className = 'text-[10px] font-medium px-1.5 py-0.5 rounded text-blue-400 bg-blue-400/10'
                    li.appendChild(badge)
                }
                list.appendChild(li)
            })
            list.classList.remove('hidden')
        })
        .catch(err => {
            loading.classList.add('hidden')
            error.textContent = err.message
            error.classList.remove('hidden')
        })
}

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

// Antigravity reports quota per window: each model group has a weekly and a rolling
// 5-hour limit that refill independently, so one bar cannot represent them all.
function renderQuotaBuckets(buckets) {
    const list = document.getElementById('usageBuckets')
    list.innerHTML = ''
    if (!buckets || !buckets.length) {
        list.classList.add('hidden')
        return
    }

    buckets.forEach(bucket => {
        const row = document.createElement('li')

        const header = document.createElement('div')
        header.className = 'flex items-center justify-between text-[11px]'

        const name = document.createElement('span')
        name.className = 'text-zinc-500'
        name.textContent = [bucket.group, bucket.label].filter(Boolean).join(' · ')
        header.appendChild(name)

        const value = document.createElement('span')
        value.className = 'text-zinc-400'
        const detail = [bucket.quota + '%']
        if (bucket.resetTime) detail.push(formatResetTime(bucket.resetTime))
        value.textContent = detail.join(' · ')
        header.appendChild(value)

        row.appendChild(header)

        const track = document.createElement('div')
        track.className = 'mt-1 h-1 bg-zinc-800 rounded-full overflow-hidden'
        const fill = document.createElement('div')
        fill.className = 'h-full rounded-full transition-all ' + quotaColor(bucket.quota)
        fill.style.width = bucket.quota + '%'
        track.appendChild(fill)
        row.appendChild(track)

        if (bucket.description) row.title = bucket.description

        list.appendChild(row)
    })
    list.classList.remove('hidden')
}

// Usage is fetched per card only once the card is on screen: the Antigravity
// endpoint takes a couple of seconds, so requesting every account up front would
// stall the page for accounts the user may never scroll to.
function loadCardUsage(card) {
    const label = card.querySelector('.card-usage-label')
    const value = card.querySelector('.card-usage-value')
    const track = card.querySelector('.card-usage-track')
    const bar = card.querySelector('.card-usage-bar')

    value.textContent = '…'

    fetch('/providers/' + card.dataset.index + '/usage')
        .then(res => res.ok ? res.json() : null)
        .then(data => {
            const usage = summarizeUsage(data)
            if (!usage) {
                label.innerHTML = '&nbsp;'
                value.textContent = ''
                return
            }
            label.textContent = usage.label
            value.textContent = usage.detail
            if (usage.percent !== null) {
                track.classList.remove('hidden')
                bar.style.width = usage.percent + '%'
                bar.className = 'card-usage-bar h-full rounded-full transition-all ' + quotaColor(usage.percent)
            }
        })
        .catch(() => {
            label.innerHTML = '&nbsp;'
            value.textContent = ''
        })
}

// Cards without a token never report usage, so they are left as-is.
const pendingUsageCards = Array.from(document.querySelectorAll('.account-card'))
    .filter(card => !card.querySelector('.card-usage').classList.contains('invisible'))

if (pendingUsageCards.length) {
    if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver((entries, obs) => {
            entries.forEach(entry => {
                if (!entry.isIntersecting) return
                obs.unobserve(entry.target)
                loadCardUsage(entry.target)
            })
        }, {rootMargin: '100px'})
        pendingUsageCards.forEach(card => observer.observe(card))
    } else {
        pendingUsageCards.forEach(loadCardUsage)
    }
}

document.querySelectorAll('.inline-edit-name').forEach(span => {
    span.addEventListener('click', function () {
        const form = this.nextElementSibling
        const input = form.querySelector('input')
        let submitted = false
        this.classList.add('hidden')
        form.classList.remove('hidden')
        input.focus()
        input.select()

        function submit() {
            if (submitted) {
                return
            }
            submitted = true
            if (input.value.trim()) {
                const url = form.action
                const body = new URLSearchParams({name: input.value.trim()})
                fetch(url, {method: 'POST', body})
                    .then(r => r.json())
                    .then(data => {
                        if (data.error) {
                            toast(data.error, 'error')
                            cancel()
                        } else {
                            span.textContent = input.value.trim()
                            cancel()
                        }
                    })
                    .catch(() => {
                        toast('Rename failed', 'error')
                        cancel()
                    })
            } else {
                cancel()
            }
        }

        function cancel() {
            form.classList.add('hidden')
            span.classList.remove('hidden')
        }

        input.addEventListener('keydown', e => {
            if (e.key === 'Enter') {
                e.preventDefault()
                submit()
            }
            if (e.key === 'Escape') {
                cancel()
            }
        })

        input.addEventListener('blur', submit)
    })
})

const titles = {
    kiro: 'Add Kiro Account',
    antigravity: 'Add Antigravity Account',
    codex: 'Add Codex Account',
    xai: 'Add xAI Account'
}
const forms = ['kiroForm', 'antigravityForm', 'codexForm', 'xaiForm']

function showProviderForm(provider) {
    document.getElementById('providerChoices').classList.add('hidden')
    document.getElementById('dialogTitle').textContent = titles[provider]
    document.getElementById(provider + 'Form').classList.remove('hidden')

    switch (provider) {
        case 'xai':
            initXaiForm()
            break
        case 'antigravity':
            initAntigravityForm()
            break
        case 'codex':
            initCodexForm()
            break
    }
}

function showChoices() {
    forms.forEach(id => {
        document.getElementById(id).classList.add('hidden')
    })
    document.getElementById('providerChoices').classList.remove('hidden')
    document.getElementById('dialogTitle').textContent = 'Add Account'
}

document.getElementById('addAccountDialog').addEventListener('close', () => {
    showChoices()
    document.getElementById('kiroFileList').innerHTML = ''
    document.getElementById('kiroFileList').classList.add('hidden')
    cancelKiroSso()
    stopOAuthPoll()
    showKiroTab('sso')
})

document.getElementById('kiroFileInput').addEventListener('change', function () {
    const list = document.getElementById('kiroFileList')
    list.innerHTML = ''
    if (this.files.length === 0) {
        list.classList.add('hidden')
        return
    }
    list.classList.remove('hidden')
    for (let i = 0; i < this.files.length; i++) {
        const f = this.files[i]
        const ext = f.name.split('.').pop().toUpperCase()
        const size = f.size < 1024 ? f.size + ' B' : (f.size / 1024).toFixed(1) + ' KB'
        const row = document.createElement('div')
        row.className = 'flex items-center gap-3 px-3 py-2 bg-zinc-950 border border-zinc-800 rounded-lg text-xs'
        row.innerHTML = '<svg class="size-4 shrink-0 text-zinc-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/></svg>' +
            '<span class="text-zinc-100 truncate flex-1">' + f.name + '</span>' +
            '<span class="text-[10px] text-zinc-500 shrink-0 uppercase">' + ext + '</span>' +
            '<span class="text-[10px] text-zinc-500 shrink-0">' + size + '</span>'
        list.appendChild(row)
    }
})

// Kiro SSO
let kiroSsoPollTimer = null

function showKiroTab(tab) {
    document.getElementById('kiroSsoTab').classList.toggle('hidden', tab !== 'sso')
    document.getElementById('kiroApikeyTab').classList.toggle('hidden', tab !== 'apikey')
    document.getElementById('kiroImportTab').classList.toggle('hidden', tab !== 'import')
    document.getElementById('kiroTabSso').classList.toggle('border-neon-purple', tab === 'sso')
    document.getElementById('kiroTabSso').classList.toggle('text-zinc-100', tab === 'sso')
    document.getElementById('kiroTabSso').classList.toggle('border-transparent', tab !== 'sso')
    document.getElementById('kiroTabSso').classList.toggle('text-zinc-500', tab !== 'sso')
    document.getElementById('kiroTabApikey').classList.toggle('border-neon-purple', tab === 'apikey')
    document.getElementById('kiroTabApikey').classList.toggle('text-zinc-100', tab === 'apikey')
    document.getElementById('kiroTabApikey').classList.toggle('border-transparent', tab !== 'apikey')
    document.getElementById('kiroTabApikey').classList.toggle('text-zinc-500', tab !== 'apikey')
    document.getElementById('kiroTabImport').classList.toggle('border-neon-purple', tab === 'import')
    document.getElementById('kiroTabImport').classList.toggle('text-zinc-100', tab === 'import')
    document.getElementById('kiroTabImport').classList.toggle('border-transparent', tab !== 'import')
    document.getElementById('kiroTabImport').classList.toggle('text-zinc-500', tab !== 'import')
}

function startKiroSso() {
    const startUrl = document.getElementById('kiroSsoStartUrl').value.trim()
    const region = document.getElementById('kiroSsoRegion').value

    if (!startUrl) {
        toast('Start URL is required', 'error')
        return
    }

    fetch('/auth/kiro/sso/start', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({startUrl, region})
    })
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                toast(data.error, 'error')
                return
            }
            document.getElementById('kiroSsoForm').classList.add('hidden')
            document.getElementById('kiroSsoVerification').classList.remove('hidden')
            document.getElementById('kiroSsoUserCode').textContent = data.userCode
            const link = document.getElementById('kiroSsoLink')
            const uri = data.verificationUriComplete || data.verificationUri
            link.href = uri
            link.textContent = uri
            document.getElementById('kiroSsoError').classList.add('hidden')

            const interval = (data.interval || 5) * 1000
            kiroSsoPollTimer = setInterval(() => {
                pollKiroSso(data.sessionId)
            }, interval)
        })
        .catch(err => {
            toast('Failed to start SSO: ' + err.message, 'error')
        })
}

function pollKiroSso(sessionId) {
    fetch('/auth/kiro/sso/poll?session=' + encodeURIComponent(sessionId))
        .then(res => res.json())
        .then(data => {
            if (data.status === 'ok') {
                clearInterval(kiroSsoPollTimer)
                kiroSsoPollTimer = null
                toast('Kiro account connected', 'success')
                document.getElementById('addAccountDialog').close()
                location.reload()
            } else if (data.status === 'expired' || data.status === 'error') {
                clearInterval(kiroSsoPollTimer)
                kiroSsoPollTimer = null
                const errorEl = document.getElementById('kiroSsoError')
                errorEl.textContent = data.message || 'Session expired'
                errorEl.classList.remove('hidden')
            }
        })
        .catch(() => {})
}

function cancelKiroSso() {
    if (kiroSsoPollTimer) {
        clearInterval(kiroSsoPollTimer)
        kiroSsoPollTimer = null
    }
    document.getElementById('kiroSsoForm').classList.remove('hidden')
    document.getElementById('kiroSsoVerification').classList.add('hidden')
    document.getElementById('kiroSsoError').classList.add('hidden')
}

// OAuth polling — detect when a new account is added
let oauthPollTimer = null
let oauthBaseCount = null

function startOAuthPoll() {
    fetch('/providers/count').then(r => r.json()).then(d => {
        oauthBaseCount = d.count
        oauthPollTimer = setInterval(() => {
            fetch('/providers/count').then(r => r.json()).then(d => {
                if (d.count > oauthBaseCount) {
                    stopOAuthPoll()
                    toast('Account connected', 'success')
                    document.getElementById('addAccountDialog').close()
                    location.reload()
                }
            }).catch(() => {})
        }, 500)
    })
}

function stopOAuthPoll() {
    if (oauthPollTimer) {
        clearInterval(oauthPollTimer)
        oauthPollTimer = null
    }
    oauthBaseCount = null
}

function initAntigravityForm() {
    const base = location.origin
    document.getElementById('agCommand').textContent = 'curl -sL "' + base + '/auth/antigravity/agent" | bash'
    startOAuthPoll()
}

function initCodexForm() {
    const isLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
    if (isLocal) {
        document.getElementById('codexLocal').classList.remove('hidden')
        document.getElementById('codexRemote').classList.add('hidden')
    } else {
        document.getElementById('codexLocal').classList.add('hidden')
        document.getElementById('codexRemote').classList.remove('hidden')
        fetch('/auth/codex/state', {method: 'POST'})
            .then(r => r.json())
            .then(data => {
                const base = location.origin
                document.getElementById('codexCommand').textContent = 'curl -sL "' + base + '/auth/codex/agent?state=' + data.state + '" | bash'
            })
    }
    startOAuthPoll()
}

function initXaiForm() {
    const isLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
    if (isLocal) {
        document.getElementById('xaiLocal').classList.remove('hidden')
        document.getElementById('xaiRemote').classList.add('hidden')
    } else {
        document.getElementById('xaiLocal').classList.add('hidden')
        document.getElementById('xaiRemote').classList.remove('hidden')
        fetch('/auth/xai/state', {method: 'POST'})
            .then(r => r.json())
            .then(data => {
                const base = location.origin
                document.getElementById('xaiCommand').textContent = 'curl -sL "' + base + '/auth/xai/agent?state=' + data.state + '" | bash'
            })
    }
    startOAuthPoll()
}
