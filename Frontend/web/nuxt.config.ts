// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
    compatibilityDate: '2025-07-15',
    devtools: {enabled: true},
    modules: ['@nuxt/ui'],
    css: ['~/assets/css/main.css'],
    app: {
        head: {
            link: [
                {
                    rel: 'preconnect',
                    href: 'https://fonts.googleapis.com'
                },
                {
                    rel: 'preconnect',
                    href: 'https://fonts.gstatic.com',
                    crossorigin: ''
                },
                {
                    rel: 'stylesheet',
                    href: 'https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap'
                }
            ]
        },
        pageTransition: { name: 'page', mode: 'out-in' },
        layoutTransition: { name: 'layout', mode: 'out-in' }
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
            backendBaseUrl: process.env.NUXT_PUBLIC_BACKEND_BASE_URL || 'http://localhost:8080',
            userId: process.env.NUXT_PUBLIC_USER_ID || ''
        }
    }
})
