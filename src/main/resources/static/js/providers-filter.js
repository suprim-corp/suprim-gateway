// Provider filter for the account cards. Purely client-side: the cards are already rendered,
// so filtering is a class toggle and the usage poll keeps covering every account.

const providerFilterSelect = document.getElementById('providerFilterSelect')

function applyProviderFilter(provider) {
    document.querySelectorAll('.account-card').forEach(card => {
        const matches = !provider || card.dataset.provider === provider
        card.classList.toggle('hidden', !matches)
    })

    const empty = document.getElementById('noFilterMatch')
    if (empty) {
        const anyVisible = document.querySelector('.account-card:not(.hidden)') !== null
        empty.classList.toggle('hidden', anyVisible)
    }

    // The selection survives a reload (delete and rename both bounce through /providers) without
    // costing a round trip to set.
    const url = new URL(window.location.href)
    if (provider) {
        url.searchParams.set('provider', provider)
    } else {
        url.searchParams.delete('provider')
    }
    window.history.replaceState({}, '', url)
}

if (providerFilterSelect) {
    providerFilterSelect.addEventListener('change', () => {
        applyProviderFilter(providerFilterSelect.value || null)
    })
    // The server marks the requested provider as selected, so the initial state comes from the
    // rendered option rather than a separate value passed into the page.
    applyProviderFilter(providerFilterSelect.value || null)
}
