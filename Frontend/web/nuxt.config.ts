// https://nuxt.com/docs/api/configuration/nuxt-config
import { fileURLToPath } from 'node:url'

const markstreamCodeRendererStub = fileURLToPath(
    new URL('./app/utils/markstreamCodeRendererStub.ts', import.meta.url)
)

const knownSourcemapWarningPlugins = [
    'nuxt:module-preload-polyfill',
    '@tailwindcss/vite:generate:build'
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
    vite: {
        optimizeDeps: {
            include: ['markstream-vue']
        },
        resolve: {
            alias: {
                'stream-markdown': markstreamCodeRendererStub
            }
        },
        build: {
            sourcemap: false,
            rollupOptions: {
                output: {
                    manualChunks,
                    onlyExplicitManualChunks: true
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
            ]
        }
    },
    ui: {
        icons: ['heroicons', 'lucide']
    },
    runtimeConfig: {
        public: {
            backendBaseUrl: process.env.NUXT_PUBLIC_BACKEND_BASE_URL || 'http://localhost:8080'
        }
    }
})
