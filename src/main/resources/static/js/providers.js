function showModels(index) {
    const dialog = document.getElementById('modelsDialog')
    const loading = document.getElementById('modelsLoading')
    const list = document.getElementById('modelsList')
    const error = document.getElementById('modelsError')
    const empty = document.getElementById('modelsEmpty')

    loading.classList.remove('hidden')
    list.classList.add('hidden')
    error.classList.add('hidden')
    empty.classList.add('hidden')
    list.innerHTML = ''
    dialog.showModal()

    fetch('/providers/' + index + '/models')
        .then(function (res) {
            if (!res.ok) throw new Error('Failed to fetch models')
            return res.json()
        })
        .then(function (models) {
            loading.classList.add('hidden')
            if (models.length === 0) {
                empty.classList.remove('hidden')
                return
            }
            models.forEach(function (m) {
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
                }
                list.appendChild(li)
            })
            list.classList.remove('hidden')
        })
        .catch(function (err) {
            loading.classList.add('hidden')
            error.textContent = err.message
            error.classList.remove('hidden')
        })
}

document.querySelectorAll('.inline-edit-name').forEach(function (span) {
    span.addEventListener('click', function () {
        const form = this.nextElementSibling
        const input = form.querySelector('input')
        this.classList.add('hidden')
        form.classList.remove('hidden')
        input.focus()
        input.select()

        function submit() {
            if (input.value.trim()) {
                form.submit()
            } else {
                cancel()
            }
        }

        function cancel() {
            form.classList.add('hidden')
            span.classList.remove('hidden')
        }

        input.addEventListener('keydown', function (e) {
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
    }
}

function showChoices() {
    forms.forEach(function (id) {
        document.getElementById(id).classList.add('hidden')
    })
    document.getElementById('providerChoices').classList.remove('hidden')
    document.getElementById('dialogTitle').textContent = 'Add Account'
}

document.getElementById('addAccountDialog').addEventListener('close', function () {
    showChoices()
    document.getElementById('kiroFileList').innerHTML = ''
    document.getElementById('kiroFileList').classList.add('hidden')
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

function initAntigravityForm() {
    const base = location.origin
    document.getElementById('agCommand').textContent = 'curl -sL "' + base + '/auth/antigravity/agent" | bash'
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
            .then(function (r) {
                return r.json()
            })
            .then(function (data) {
                const base = location.origin
                document.getElementById('xaiCommand').textContent = 'curl -sL "' + base + '/auth/xai/agent?state=' + data.state + '" | bash'
            })
    }
}
