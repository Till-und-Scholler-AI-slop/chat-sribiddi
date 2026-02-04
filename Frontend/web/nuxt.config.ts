// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
    compatibilityDate: '2025-07-15',
    devtools: {enabled: true},
    modules: ['@nuxt/ui'],
    css: ['~/assets/css/main.css'],
    ui: {
        icons: ['heroicons']
    },
    runtimeConfig: {
        public: {
            backendBaseUrl: process.env.NUXT_PUBLIC_BACKEND_BASE_URL || 'http://localhost:8080',
            userId: process.env.NUXT_PUBLIC_USER_ID || ''
        }
    }
})
