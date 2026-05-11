function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
}

function renderPlainCode(container: HTMLElement, code: string) {
    container.innerHTML = `<pre class="shiki shiki-fallback"><code>${escapeHtml(code)}</code></pre>`
}

export function createShikiStreamRenderer(container: HTMLElement) {
    let currentCode = ''
    let currentLanguage: string | undefined

    return {
        async updateCode(code: string, language?: string) {
            currentCode = code
            currentLanguage = language
            renderPlainCode(container, code)
        },
        async setTheme() {
            // The lightweight fallback is styled through Markstream/CSS variables.
        },
        dispose() {
            currentCode = ''
            currentLanguage = undefined
        },
        getState() {
            return {
                code: currentCode,
                lang: currentLanguage,
                theme: 'plain'
            }
        }
    }
}

export function registerHighlight() {
    return Promise.resolve()
}

export function disposeHighlighter() {}
