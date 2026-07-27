// Account cards: lazy usage loading and inline rename.
// Depends on providers-usage.js.

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
