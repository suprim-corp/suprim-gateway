// Account cards: live usage polling and inline rename.
// Depends on providers-usage.js.

const USAGE_POLL_MS = 5000

// Each card polls its own account on its own timer. One slow provider then only delays its own
// card instead of holding up every other card's figures.
const usageCards = Array.from(document.querySelectorAll('.account-card'))
    .filter(card => !card.querySelector('.card-usage').classList.contains('invisible'))

function renderCardUsage(card, data) {
    const label = card.querySelector('.card-usage-label')
    const value = card.querySelector('.card-usage-value')
    const track = card.querySelector('.card-usage-track')
    const bar = card.querySelector('.card-usage-bar')

    const usage = summarizeUsage(data)
    if (!usage) {
        label.innerHTML = '&nbsp;'
        value.textContent = ''
        track.classList.add('hidden')
        return
    }
    label.textContent = usage.label
    value.textContent = usage.detail
    if (usage.percent === null) {
        track.classList.add('hidden')
        return
    }
    track.classList.remove('hidden')
    bar.style.width = usage.percent + '%'
    bar.className = 'card-usage-bar h-full rounded-full transition-all ' + quotaColor(usage.percent)
}

// Timers are held per card so a hidden tab can stop them all and resume later.
const pollTimers = new Map()

function pollCard(card) {
    fetch('/providers/' + card.dataset.index + '/usage')
        .then(res => res.ok ? res.json() : null)
        .then(data => renderCardUsage(card, data))
        .catch(() => {})
        .finally(() => {
            // Scheduled only after the response lands: a provider slower than the interval must
            // not stack requests on top of itself.
            if (document.hidden) {
                pollTimers.delete(card.dataset.index)
                return
            }
            pollTimers.set(card.dataset.index, setTimeout(() => pollCard(card), USAGE_POLL_MS))
        })
}

function startPolling() {
    usageCards.forEach((card, i) => {
        if (pollTimers.has(card.dataset.index)) return
        // Staggered so a dozen accounts do not fire at the same instant on every tick.
        pollTimers.set(card.dataset.index, setTimeout(() => pollCard(card), i * 120))
    })
}

function stopPolling() {
    pollTimers.forEach(timer => clearTimeout(timer))
    pollTimers.clear()
}

// Polling a hidden tab wakes the upstreams for nothing, so it pauses until the page is visible
// again and then refreshes immediately.
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopPolling()
    } else {
        startPolling()
    }
})

if (usageCards.length) {
    usageCards.forEach(card => {
        card.querySelector('.card-usage-value').textContent = '…'
    })
    startPolling()
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
