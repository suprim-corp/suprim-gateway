// Models dialog: model list plus the usage banner for one account.
// Depends on providers-usage.js.

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

        const hasPercent = bucket.quota !== undefined && bucket.quota !== null

        const value = document.createElement('span')
        value.className = 'text-zinc-400'
        const detail = [hasPercent ? bucket.quota + '%' : bucket.remaining + ' left']
        if (bucket.resetTime) detail.push(formatResetTime(bucket.resetTime))
        value.textContent = detail.join(' · ')
        header.appendChild(value)

        row.appendChild(header)

        // A count-based window reports what is left but not the total, so there is no
        // ratio to fill a bar with. The number alone carries the information.
        if (hasPercent) {
            const track = document.createElement('div')
            track.className = 'mt-1 h-1 bg-zinc-800 rounded-full overflow-hidden'
            const fill = document.createElement('div')
            fill.className = 'h-full rounded-full transition-all ' + quotaColor(bucket.quota)
            fill.style.width = bucket.quota + '%'
            track.appendChild(fill)
            row.appendChild(track)
        }

        if (bucket.description) row.title = bucket.description

        list.appendChild(row)
    })
    list.classList.remove('hidden')
}
