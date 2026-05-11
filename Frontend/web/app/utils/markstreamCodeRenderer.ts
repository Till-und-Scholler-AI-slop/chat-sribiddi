import { createBundledHighlighter } from 'shiki/core'
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript'
import githubDarkDimmed from '@shikijs/themes/github-dark-dimmed'
import githubLight from '@shikijs/themes/github-light'

const bundledThemes = {
    'github-light': githubLight,
    'github-dark-dimmed': githubDarkDimmed
} as const

const bundledLanguages = {
    bash: () => import('@shikijs/langs/bash'),
    css: () => import('@shikijs/langs/css'),
    dockerfile: () => import('@shikijs/langs/dockerfile'),
    go: () => import('@shikijs/langs/go'),
    html: () => import('@shikijs/langs/html'),
    java: () => import('@shikijs/langs/java'),
    javascript: () => import('@shikijs/langs/javascript'),
    json: () => import('@shikijs/langs/json'),
    jsx: () => import('@shikijs/langs/jsx'),
    kotlin: () => import('@shikijs/langs/kotlin'),
    markdown: () => import('@shikijs/langs/markdown'),
    python: () => import('@shikijs/langs/python'),
    rust: () => import('@shikijs/langs/rust'),
    sql: () => import('@shikijs/langs/sql'),
    swift: () => import('@shikijs/langs/swift'),
    tsx: () => import('@shikijs/langs/tsx'),
    typescript: () => import('@shikijs/langs/typescript'),
    vue: () => import('@shikijs/langs/vue'),
    xml: () => import('@shikijs/langs/xml'),
    yaml: () => import('@shikijs/langs/yaml')
} as const

type BundledLanguage = keyof typeof bundledLanguages
type BundledTheme = keyof typeof bundledThemes

export const defaultThemes = Object.keys(bundledThemes) as BundledTheme[]
export const defaultLanguages = [
    ...Object.keys(bundledLanguages),
    'cjs',
    'js',
    'mjs',
    'md',
    'node',
    'plain',
    'plaintext',
    'py',
    'sh',
    'shell',
    'shellscript',
    'text',
    'ts',
    'txt',
    'yml',
    'zsh'
]

const languageAliases: Record<string, BundledLanguage | 'plaintext'> = {
    cjs: 'javascript',
    js: 'javascript',
    mjs: 'javascript',
    md: 'markdown',
    node: 'javascript',
    plain: 'plaintext',
    py: 'python',
    sh: 'bash',
    shell: 'bash',
    shellscript: 'bash',
    text: 'plaintext',
    ts: 'typescript',
    txt: 'plaintext',
    yml: 'yaml',
    zsh: 'bash'
}

const languageDependencies: Partial<Record<BundledLanguage, BundledLanguage[]>> = {
    jsx: ['javascript'],
    markdown: ['javascript', 'typescript', 'html', 'css'],
    tsx: ['javascript', 'typescript', 'jsx'],
    vue: ['javascript', 'typescript', 'html', 'css']
}

const createHighlighter = createBundledHighlighter({
    langs: bundledLanguages,
    themes: bundledThemes,
    engine: () => createJavaScriptRegexEngine()
})

let highlighterPromise: ReturnType<typeof createHighlighter> | null = null
let highlighter: Awaited<ReturnType<typeof createHighlighter>> | null = null

function normalizeLanguage(language?: string): BundledLanguage | 'plaintext' {
    const raw = String(language || 'plaintext').split(':')[0]?.trim().toLowerCase() || 'plaintext'
    const alias = languageAliases[raw]

    if (alias) return alias
    if (raw in bundledLanguages) return raw as BundledLanguage

    return 'plaintext'
}

function normalizeTheme(theme?: string): BundledTheme {
    return theme === 'github-dark-dimmed' ? 'github-dark-dimmed' : 'github-light'
}

async function getHighlighter() {
    if (highlighter) return highlighter

    highlighterPromise ??= createHighlighter({
        themes: defaultThemes,
        langs: []
    })

    highlighter = await highlighterPromise
    return highlighter
}

async function ensureLanguage(language: BundledLanguage | 'plaintext') {
    if (language === 'plaintext') return

    const highlighter = await getHighlighter()
    const loadedLanguages = new Set(highlighter.getLoadedLanguages())
    const languages = [...(languageDependencies[language] || []), language]

    for (const lang of languages) {
        if (!loadedLanguages.has(lang)) {
            await highlighter.loadLanguage(lang)
            loadedLanguages.add(lang)
        }
    }
}

function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
}

function renderPlainCode(code: string): string {
    return `<pre class="shiki shiki-fallback"><code>${escapeHtml(code)}</code></pre>`
}

async function renderHighlightedCode(code: string, language?: string, theme?: string): Promise<string> {
    const normalizedLanguage = normalizeLanguage(language)
    const normalizedTheme = normalizeTheme(theme)

    try {
        await ensureLanguage(normalizedLanguage)

        const highlighter = await getHighlighter()
        return highlighter.codeToHtml(code, {
            lang: normalizedLanguage,
            theme: normalizedTheme
        })
    } catch {
        return renderPlainCode(code)
    }
}

export async function registerHighlight(options: {
    themes?: Array<string>
    langs?: Array<string>
} = {}) {
    const highlighter = await getHighlighter()
    await Promise.all((options.langs || []).map(lang => ensureLanguage(normalizeLanguage(lang))))

    return highlighter
}

export function createShikiStreamRenderer(container: HTMLElement, options: {
    lang?: string
    theme?: string
} = {}) {
    let currentCode = ''
    let currentLanguage = options.lang
    let currentTheme = normalizeTheme(options.theme)
    let renderVersion = 0
    let disposed = false

    async function render() {
        const version = ++renderVersion
        const html = currentCode
            ? await renderHighlightedCode(currentCode, currentLanguage, currentTheme)
            : ''

        if (!disposed && version === renderVersion) {
            container.innerHTML = html
        }
    }

    return {
        async updateCode(code: string, language?: string) {
            currentCode = code
            currentLanguage = language || currentLanguage
            await render()
        },
        async setTheme(theme: string) {
            const nextTheme = normalizeTheme(theme)
            if (nextTheme === currentTheme) return

            currentTheme = nextTheme
            await render()
        },
        dispose() {
            disposed = true
            currentCode = ''
            currentLanguage = undefined
        },
        getState() {
            return {
                code: currentCode,
                lang: currentLanguage,
                theme: currentTheme
            }
        }
    }
}

export function disposeHighlighter() {
    highlighter?.dispose()
    highlighter = null
    highlighterPromise = null
}
