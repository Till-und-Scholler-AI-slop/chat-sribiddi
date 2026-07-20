// https://nuxt.com/docs/api/configuration/nuxt-config
import { fileURLToPath } from 'node:url'

const markstreamCodeRenderer = fileURLToPath(
    new URL('./app/utils/markstreamCodeRenderer.ts', import.meta.url)
)

const knownSourcemapWarningPlugins = [
    'nuxt:module-preload-polyfill',
    '@tailwindcss/vite:generate:build'
]

const markdownHighlightOptimizeDeps = [
    '@shikijs/langs/bash',
    '@shikijs/langs/css',
    '@shikijs/langs/dockerfile',
    '@shikijs/langs/go',
    '@shikijs/langs/html',
    '@shikijs/langs/java',
    '@shikijs/langs/javascript',
    '@shikijs/langs/json',
    '@shikijs/langs/jsx',
    '@shikijs/langs/kotlin',
    '@shikijs/langs/markdown',
    '@shikijs/langs/python',
    '@shikijs/langs/rust',
    '@shikijs/langs/sql',
    '@shikijs/langs/swift',
    '@shikijs/langs/tsx',
    '@shikijs/langs/typescript',
    '@shikijs/langs/vue',
    '@shikijs/langs/xml',
    '@shikijs/langs/yaml',
    '@shikijs/themes/github-dark-dimmed',
    '@shikijs/themes/github-light',
    'shiki/core',
    'shiki/engine/javascript'
]

type RollupBuildLog = {
    code?: string
    message?: string
    plugin?: string
}

function isKnownSourcemapWarning(log: RollupBuildLog): boolean {
    const text = `${log.plugin ?? ''} ${log.message ?? ''}`

    return log.code === 'SOURCEMAP_BROKEN'
        && text.includes('Sourcemap is likely to be incorrect')
        && knownSourcemapWarningPlugins.some(plugin => text.includes(plugin))
}

function manualChunks(id: string): string | undefined {
    const normalizedId = id.split('\\').join('/')

    if (
        normalizedId.includes('/node_modules/stream-markdown-parser/')
        || normalizedId.includes('/node_modules/markdown-it')
    ) {
        return 'markdown-parser'
    }

    if (
        normalizedId.includes('/node_modules/shiki/')
        || normalizedId.includes('/node_modules/shiki-stream/')
    ) {
        return 'markdown-highlight'
    }

    const shikiLanguage = normalizedId.match(
        /\/node_modules\/@shikijs\/langs\/dist\/([^/.]+)\.mjs$/
    )
    if (shikiLanguage?.[1]) {
        return `shiki-lang-${shikiLanguage[1]}`
    }

    if (normalizedId.includes('/node_modules/@shikijs/themes/')) {
        return 'shiki-themes'
    }

    if (normalizedId.includes('/node_modules/@shikijs/')) {
        return 'markdown-highlight'
    }

    if (
        normalizedId.includes('/node_modules/markstream-vue/')
        || normalizedId.includes('/node_modules/markstream-core/')
        || normalizedId.includes('/node_modules/@chenglou/pretext/')
    ) {
        return 'markstream-vue'
    }
}

export default defineNuxtConfig({
    compatibilityDate: '2025-07-15',
    devtools: {enabled: process.env.NUXT_DEVTOOLS !== 'false'},
    modules: ['@nuxt/ui', '@nuxt/eslint', '@nuxtjs/i18n'],
    css: ['~/assets/css/main.css'],
    sourcemap: false,
    routeRules: {
        '/chat': { ssr: false },
        '/compare': { ssr: false },
        '/models': { ssr: false },
        '/settings': { ssr: false },
        '/usage': { ssr: false },
        '/billing': { ssr: false }
    },
    vite: {
        optimizeDeps: {
            include: markdownHighlightOptimizeDeps,
            exclude: ['markstream-vue', 'stream-markdown']
        },
        resolve: {
            alias: {
                'stream-markdown': markstreamCodeRenderer
            }
        },
        build: {
            sourcemap: false,
            rollupOptions: {
                output: {
                    manualChunks
                },
                onLog(level, log, handler) {
                    if (isKnownSourcemapWarning(log)) return
                    handler(level, log)
                },
                onwarn(warning, defaultHandler) {
                    if (isKnownSourcemapWarning(warning)) return
                    defaultHandler(warning)
                }
            }
        }
    },
    i18n: {
        // German is the default locale; English is fully supported as a second locale.
        // See AGENTS.md for the project i18n conventions (every user-facing string must be
        // translated and added to BOTH locale files).
        defaultLocale: 'de',
        strategy: 'no_prefix',
        locales: [
            { code: 'de', name: 'Deutsch', language: 'de-DE', file: 'de.json' },
            { code: 'en', name: 'English', language: 'en-US', file: 'en.json' }
        ],
        bundle: {
            optimizeTranslationDirective: false
        },
        detectBrowserLanguage: {
            useCookie: true,
            cookieKey: 'i18n_redirected',
            redirectOn: 'root',
            // If the browser language matches `de`, the user stays on German.
            // Every other detected language falls back to English.
            fallbackLocale: 'en'
        },
        vueI18n: './i18n/i18n.config.ts'
    },
    app: {
        head: {
            title: 'chat-sribiddi – AI Chat UI',
            meta: [
                {
                    name: 'description',
                    content: 'A lightweight AI chat wrapper supporting multiple providers.'
                },
                {
                    name: 'theme-color',
                    content: '#b82e8e'
                }
            ],
            link: [
                {
                    rel: 'icon',
                    type: 'image/x-icon',
                    href: '/favicon.ico'
                },
                {
                    rel: 'manifest',
                    href: '/manifest.webmanifest'
                },
            ]
        },
        pageTransition: { name: 'page', mode: 'out-in' },
        layoutTransition: { name: 'layout', mode: 'out-in' }
    },
    fonts: {
        providers: {
            adobe: false,
            bunny: false,
            fontshare: false,
            fontsource: false,
            google: false,
            googleicons: false,
            npm: false
        }
    },
    icon: {
        provider: 'none',
        mode: 'css',
        clientBundle: {
            scan: true,
            icons: [
                'lucide:menu',
                'lucide:x',
                'lucide:chevron-down',
                'lucide:arrow-up',
                // USelectMenu / UCheckbox internal icons
                'lucide:check',
                'lucide:square',
                'lucide:arrow-down',
                // Nuxt UI loading state
                'lucide:loader-circle',
                // UPagination internal icons
                'lucide:chevrons-left',
                'lucide:chevron-left',
                'lucide:chevron-right',
                'lucide:chevrons-right',
                // Provider setup logos
                'simple-icons:anthropic',
                'simple-icons:brave',
                'simple-icons:deepseek',
                'simple-icons:githubcopilot',
                'simple-icons:googlegemini',
                'simple-icons:lmstudio',
                'simple-icons:minimax',
                'simple-icons:mistralai',
                'simple-icons:ollama',
                'simple-icons:openai',
                'simple-icons:openrouter',
                'simple-icons:perplexity',
                'simple-icons:qwen',
                'simple-icons:vllm',
            ]
        }
    },
    ui: {
        icons: ['heroicons', 'lucide', 'simple-icons']
    },
    runtimeConfig: {
        public: {
            backendBaseUrl: process.env.NUXT_PUBLIC_BACKEND_BASE_URL || 'http://localhost:8080'
        }
    }
})
