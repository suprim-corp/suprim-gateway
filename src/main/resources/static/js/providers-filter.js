const providerFilterSelect = document.getElementById('providerFilterSelect')

if (providerFilterSelect) {
    providerFilterSelect.addEventListener('change', () => {
        const provider = providerFilterSelect.value
        window.location.href = provider
            ? '/providers?provider=' + encodeURIComponent(provider)
            : '/providers'
    })
}
