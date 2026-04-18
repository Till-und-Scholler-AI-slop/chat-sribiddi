<script setup lang="ts">
import { DEFAULT_BACKEND_BASE_URL, normalizeBaseUrl } from '~/composables/useChatApi'

const { t } = useI18n()

definePageMeta({
  layout: 'login'
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
  try {
    const parsed = new URL(trimmed)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return DEFAULT_BACKEND_BASE_URL
    }
    return normalizeBaseUrl(parsed.toString())
  } catch {
    return DEFAULT_BACKEND_BASE_URL
  }
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
    checkError.value = err instanceof Error ? err.message : t('auth.checkSessionFailed')
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
        <h1 class="login-brand-title">{{ t('auth.loginTitle') }}</h1>
        <p class="login-brand-subtitle">{{ t('auth.loginSubtitle') }}</p>
      </div>

      <div class="login-card glass-surface">
        <div v-if="checking" class="space-y-4 py-2">
          <div class="skeleton-shimmer h-12 w-full rounded-xl" />
          <div class="skeleton-shimmer h-4 w-3/4 mx-auto rounded-lg" />
          <p class="text-center text-sm text-gray-500 dark:text-gray-400 pt-1">
            {{ t('auth.checkingSession') }}
          </p>
        </div>

        <template v-else>
          <p class="login-card-text">
            {{ t('auth.loginCta') }}
          </p>

          <a :href="loginUrl" class="google-btn btn-interactive">
            <svg class="google-btn-svg" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
              <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
              <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
              <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
            </svg>
            <span>{{ t('auth.loginButton') }}</span>
          </a>

          <p class="login-card-hint">
            {{ t('auth.loginHint') }}
          </p>
        </template>

        <UAlert
          v-if="checkError"
          color="error"
          variant="soft"
          icon="i-heroicons-x-circle"
          :title="t('auth.checkSessionFailedTitle')"
          :description="checkError"
          class="mt-4"
        />
      </div>

      <div class="login-features stagger-children">
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-cpu-chip" class="size-4" />
          </div>
          <span>{{ t('auth.featureModels') }}</span>
        </div>
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-globe-alt" class="size-4" />
          </div>
          <span>{{ t('auth.featureWebSearch') }}</span>
        </div>
        <div class="login-feature">
          <div class="login-feature-icon">
            <UIcon name="i-heroicons-paper-clip" class="size-4" />
          </div>
          <span>{{ t('auth.featureFiles') }}</span>
        </div>
      </div>

      <p class="login-legal">
        <NuxtLink to="/impressum">{{ t('legal.impressum.title') }}</NuxtLink>
        <span class="login-legal-dot">&middot;</span>
        <NuxtLink to="/datenschutz">{{ t('legal.datenschutz.title') }}</NuxtLink>
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
    radial-gradient(ellipse 80% 60% at 20% 20%, color-mix(in oklab, var(--accent) 8%, transparent) 0%, transparent 60%),
    radial-gradient(ellipse 60% 50% at 80% 80%, color-mix(in oklab, var(--accent) 5%, transparent) 0%, transparent 50%),
    var(--paper);
}
.dark .login-page {
  background:
    radial-gradient(ellipse 80% 60% at 20% 20%, color-mix(in oklab, var(--accent) 12%, transparent) 0%, transparent 60%),
    radial-gradient(ellipse 60% 50% at 80% 80%, color-mix(in oklab, var(--accent) 8%, transparent) 0%, transparent 50%),
    var(--paper);
}

.login-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(44px);
  pointer-events: none;
  opacity: 0.38;
}
.dark .login-orb {
  opacity: 0.32;
}
.login-orb--1 {
  width: 320px;
  height: 320px;
  top: -10%;
  left: -8%;
  background: color-mix(in oklab, var(--accent) 18%, transparent);
}
.login-orb--2 {
  width: 260px;
  height: 260px;
  bottom: -8%;
  right: -6%;
  background: color-mix(in oklab, var(--accent) 14%, transparent);
}
.login-orb--3 {
  width: 190px;
  height: 190px;
  top: 50%;
  left: 60%;
  background: color-mix(in oklab, var(--accent) 10%, transparent);
}
.login-orb--4 {
  width: 140px;
  height: 140px;
  bottom: 20%;
  left: 5%;
  background: color-mix(in oklab, var(--accent) 10%, transparent);
}

@media (prefers-reduced-motion: no-preference) {
  .login-orb--1,
  .login-orb--2 {
    animation: login-orb-float 20s ease-in-out infinite;
  }
  .login-orb--2 { animation-delay: -6s; animation-duration: 24s; }
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
  background: var(--accent);
  color: var(--paper);
  margin-bottom: 1rem;
  box-shadow: 0 4px 16px color-mix(in oklab, var(--accent) 35%, transparent);
}

.login-brand-title {
  font-size: 2rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  line-height: 1.1;
  color: var(--accent-ink);
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
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--ui-primary) 78%, black) 0%,
    color-mix(in srgb, var(--ui-primary) 58%, black) 100%
  );
  color: #fff;
  font-weight: 600;
  font-size: 0.9375rem;
  text-decoration: none;
  box-shadow:
    0 1px 2px color-mix(in srgb, var(--ui-primary) 25%, transparent),
    0 4px 16px color-mix(in srgb, var(--ui-primary) 20%, transparent);
  transition:
    transform var(--duration-fast) var(--ease-smooth),
    box-shadow var(--duration-fast) var(--ease-smooth),
    background var(--duration-fast) var(--ease-smooth);
}
.google-btn:hover {
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--ui-primary) 88%, black) 0%,
    color-mix(in srgb, var(--ui-primary) 68%, black) 100%
  );
  box-shadow:
    0 2px 4px color-mix(in srgb, var(--ui-primary) 30%, transparent),
    0 8px 24px color-mix(in srgb, var(--ui-primary) 25%, transparent);
  color: #fff;
  text-decoration: none;
}

.google-btn-svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.login-card-hint {
  margin-top: 1rem;
  text-align: center;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: var(--color-gray-500);
}
.dark .login-card-hint {
  color: var(--color-gray-400);
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
  border: 1px solid var(--hair);
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
