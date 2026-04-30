// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
    compatibilityDate: '2025-07-15',
    devtools: {enabled: true},
    modules: ['@nuxt/ui', '@nuxt/eslint'],
    css: ['~/assets/css/main.css'],
    app: {
        head: {
            title: 'chat-sribiddi – AI Chat UI',
            meta: [
                {
                    name: 'description',
                    content: 'A lightweight AI chat wrapper supporting multiple providers.'
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
