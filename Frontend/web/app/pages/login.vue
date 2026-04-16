<script setup lang="ts">
import { DEFAULT_BACKEND_BASE_URL, normalizeBaseUrl } from '~/composables/useChatApi'

definePageMeta({
  layout: 'auth'
})

const { settings } = useUserSettings()
const { checkAuth } = useAuth()
const router = useRouter()
const checking = ref(true)
const authState = ref<'unknown' | 'authenticated' | 'unauthenticated'>('unknown')
const checkError = ref<string | null>(null)

const resolveBaseUrl = (value: string) => {
  const trimmed = value.trim()
  if (!trimmed) return DEFAULT_BACKEND_BASE_URL
  return normalizeBaseUrl(trimmed)
}

const loginUrl = computed(() => `${resolveBaseUrl(settings.value.backendBaseUrl)}/oauth2/authorization/google`)

onMounted(async () => {
  try {
    const state = await checkAuth()
    authState.value = state
    if (state === 'authenticated') {
      await router.replace('/chat')
      return
    }
  } catch (err) {
    checkError.value = err instanceof Error ? err.message : 'Sitzung konnte nicht geprüft werden.'
    authState.value = 'unauthenticated'
  } finally {
    checking.value = false
  }
})
</script>

<template>
  <div class="login-page">
    <div class="login-orb login-orb--1" />
    <div class="login-orb login-orb--2" />
    <div class="login-orb login-orb--3" />
    <div class="login-orb login-orb--4" />

    <div class="login-content animate-fade-in-up">
      <div class="login-brand">
        <div class="login-brand-icon">
          <UIcon name="i-heroicons-chat-bubble-left-right" class="size-7" />
        </div>
        <h1 class="login-brand-title">Chat Sribiddi</h1>
        <p class="login-brand-subtitle">Dein intelligenter KI-Assistent</p>
      </div>

      <div class="login-card glass-surface">
        <div v-if="checking" class="space-y-4 py-2">
          <div class="skeleton-shimmer h-12 w-full rounded-xl" />
          <div class="skeleton-shimmer h-4 w-3/4 mx-auto rounded-lg" />
          <p class="text-center text-sm text-gray-500 dark:text-gray-400 pt-1">
            Sitzung wird geprüft …
          </p>
        </div>

        <template v-else>
          <p class="login-card-text">
            Melde dich mit deinem Google-Konto an, um auf deine Chats und KI-Modelle zuzugreifen.
          </p>

          <a :href="loginUrl" class="google-btn btn-interactive">
            <svg class="google-btn-svg" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
              <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
              <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
              <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
            </svg>
            <span>Mit Google anmelden</span>
          </a>
        </template>

        <UAlert
          v-if="checkError"
          color="error"
          variant="soft"
          icon="i-heroicons-x-circle"
          title="Sitzungsprüfung fehlgeschlagen"
          :description="checkError"
          class="mt-4"
        />
      </div>

      <div class="login-features stagger-children">
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-cpu-chip" class="size-4" />
          </div>
          <span>Mehrere KI-Modelle</span>
        </div>
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-globe-alt" class="size-4" />
          </div>
          <span>Web-Suche</span>
        </div>
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-paper-clip" class="size-4" />
          </div>
          <span>Dateianalyse</span>
        </div>
      </div>

      <p class="login-legal">
        <NuxtLink to="/impressum">Impressum</NuxtLink>
        <span class="login-legal-dot">&middot;</span>
        <NuxtLink to="/datenschutz">Datenschutz</NuxtLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    radial-gradient(ellipse 80% 60% at 20% 20%, rgba(184, 46, 142, 0.06) 0%, transparent 60%),
    radial-gradient(ellipse 60% 50% at 80% 80%, rgba(58, 140, 207, 0.05) 0%, transparent 50%),
    #F0F9FF;
}
.dark .login-page {
  background:
    radial-gradient(ellipse 80% 60% at 20% 20%, rgba(229, 51, 138, 0.1) 0%, transparent 60%),
    radial-gradient(ellipse 60% 50% at 80% 80%, rgba(58, 88, 180, 0.08) 0%, transparent 50%),
    #0B0514;
}

.login-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  opacity: 0.5;
}
.dark .login-orb {
  opacity: 0.4;
}
.login-orb--1 {
  width: 500px;
  height: 500px;
  top: -15%;
  left: -10%;
  background: rgba(184, 46, 142, 0.15);
}
.dark .login-orb--1 {
  background: rgba(229, 51, 138, 0.2);
}
.login-orb--2 {
  width: 400px;
  height: 400px;
  bottom: -10%;
  right: -8%;
  background: rgba(58, 140, 207, 0.12);
}
.dark .login-orb--2 {
  background: rgba(91, 184, 232, 0.15);
}
.login-orb--3 {
  width: 300px;
  height: 300px;
  top: 50%;
  left: 60%;
  background: rgba(184, 46, 142, 0.08);
}
.dark .login-orb--3 {
  background: rgba(229, 51, 138, 0.12);
}
.login-orb--4 {
  width: 200px;
  height: 200px;
  bottom: 20%;
  left: 5%;
  background: rgba(58, 140, 207, 0.08);
}
.dark .login-orb--4 {
  background: rgba(91, 184, 232, 0.1);
}

@media (prefers-reduced-motion: no-preference) {
  .login-orb {
    animation: login-orb-float 20s ease-in-out infinite;
  }
  .login-orb--2 { animation-delay: -5s; animation-duration: 25s; }
  .login-orb--3 { animation-delay: -10s; animation-duration: 22s; }
  .login-orb--4 { animation-delay: -15s; animation-duration: 18s; }
}

@keyframes login-orb-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25%      { transform: translate(30px, -20px) scale(1.05); }
  50%      { transform: translate(-20px, 15px) scale(0.95); }
  75%      { transform: translate(15px, 25px) scale(1.03); }
}

.login-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2rem;
  padding: 2rem 1.25rem;
  width: 100%;
  max-width: 440px;
}

.login-brand {
  text-align: center;
}

.login-brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: linear-gradient(135deg, #B82E8E, #3A8CCF);
  color: #fff;
  margin-bottom: 1rem;
  box-shadow: 0 4px 16px rgba(184, 46, 142, 0.3);
}
.dark .login-brand-icon {
  background: linear-gradient(135deg, #E5338A, #5BB8E8);
  box-shadow: 0 4px 20px rgba(229, 51, 138, 0.35);
}

.login-brand-title {
  font-size: 2rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  line-height: 1.1;
  background: linear-gradient(135deg, #B82E8E, #3A8CCF);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.dark .login-brand-title {
  background: linear-gradient(135deg, #E5338A, #5BB8E8);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.login-brand-subtitle {
  margin-top: 0.5rem;
  font-size: 0.9375rem;
  color: var(--color-gray-600);
  letter-spacing: -0.01em;
}
.dark .login-brand-subtitle {
  color: var(--color-gray-300);
}

.login-card {
  width: 100%;
  border-radius: 1.25rem;
  border: 1px solid rgba(255, 255, 255, 0.6);
  padding: 2rem;
  box-shadow:
    0 1px 3px rgba(0, 0, 0, 0.06),
    0 8px 32px rgba(0, 0, 0, 0.1);
}
.dark .login-card {
  border-color: rgba(255, 255, 255, 0.08);
  box-shadow:
    0 1px 3px rgba(0, 0, 0, 0.3),
    0 8px 32px rgba(0, 0, 0, 0.3);
}

.login-card-text {
  text-align: center;
  font-size: 0.9375rem;
  color: var(--color-gray-600);
  line-height: 1.5;
  margin-bottom: 1.5rem;
}
.dark .login-card-text {
  color: var(--color-gray-300);
}

.google-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.8rem 1.5rem;
  border-radius: 0.875rem;
  background: linear-gradient(135deg, #B82E8E 0%, #9B1E6E 100%);
  color: #fff;
  font-weight: 600;
  font-size: 0.9375rem;
  text-decoration: none;
  box-shadow:
    0 1px 2px rgba(155, 30, 110, 0.2),
    0 4px 16px rgba(184, 46, 142, 0.15);
  transition:
    transform var(--duration-fast) var(--ease-smooth),
    box-shadow var(--duration-fast) var(--ease-smooth),
    background var(--duration-fast) var(--ease-smooth);
}
.google-btn:hover {
  background: linear-gradient(135deg, #C9389E 0%, #A8247E 100%);
  box-shadow:
    0 2px 4px rgba(155, 30, 110, 0.25),
    0 8px 24px rgba(184, 46, 142, 0.2);
  color: #fff;
  text-decoration: none;
}
.dark .google-btn {
  background: linear-gradient(135deg, #E5338A 0%, #B82E8E 100%);
  box-shadow:
    0 1px 2px rgba(0, 0, 0, 0.3),
    0 4px 16px rgba(229, 51, 138, 0.2);
}
.dark .google-btn:hover {
  background: linear-gradient(135deg, #F0439A 0%, #C9389E 100%);
  box-shadow:
    0 2px 4px rgba(0, 0, 0, 0.4),
    0 8px 24px rgba(229, 51, 138, 0.25);
}

.google-btn-svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.login-features {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.75rem;
}

.login-feature {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.875rem;
  border-radius: 9999px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--color-gray-600);
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(58, 140, 207, 0.15);
}
.dark .login-feature {
  color: var(--color-gray-300);
  background: rgba(20, 10, 33, 0.6);
  border-color: rgba(255, 255, 255, 0.08);
}

.login-feature-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ui-primary);
}

.login-legal {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: var(--color-gray-500);
}
.login-legal a {
  color: var(--color-gray-600);
  text-decoration: none;
  transition: color var(--duration-fast) var(--ease-smooth);
  border-bottom: 1px solid transparent;
}
.dark .login-legal {
  color: var(--color-gray-500);
}
.dark .login-legal a {
  color: var(--color-gray-400);
}
.login-legal a:hover {
  color: var(--ui-primary);
  border-bottom-color: var(--ui-primary);
}
.login-legal-dot {
  color: var(--color-gray-300);
}
</style>
