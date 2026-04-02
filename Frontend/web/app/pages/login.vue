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
    checkError.value = err instanceof Error ? err.message : 'Could not verify session.'
    authState.value = 'unauthenticated'
  } finally {
    checking.value = false
  }
})
</script>

<template>
  <div class="mx-auto mt-16 max-w-md animate-fade-in-up">
    <UCard class="card-elevated">
      <div class="space-y-8 py-2">
        <div class="space-y-3 text-center">
          <div class="welcome-hero welcome-hero-login">
            <h2>Chat Sribiddi</h2>
          </div>
          <p class="text-sm text-gray-600 dark:text-gray-400">
            Sign in with your Google account to continue.
          </p>
        </div>

        <div v-if="checking" class="space-y-3">
          <div class="skeleton-shimmer h-12 w-full" />
          <p class="text-center text-xs text-gray-500 dark:text-gray-400">
            Checking current session...
          </p>
        </div>

        <div v-else class="space-y-3">
          <UButton
            block
            color="primary"
            icon="i-heroicons-arrow-right-circle"
            :href="loginUrl"
            size="xl"
            label="Continue with Google"
            class="btn-interactive"
          />
        </div>

        <UAlert
          v-if="checkError"
          color="error"
          variant="soft"
          icon="i-heroicons-x-circle"
          title="Session check failed"
          :description="checkError"
        />

        <p class="text-center text-xs text-gray-500 dark:text-gray-400">
          Legal:
          <NuxtLink to="/impressum" class="underline underline-offset-2 hover:text-gray-700 dark:hover:text-gray-300 transition-colors">Impressum</NuxtLink>
          and
          <NuxtLink to="/datenschutz" class="underline underline-offset-2 hover:text-gray-700 dark:hover:text-gray-300 transition-colors">Datenschutz</NuxtLink>.
        </p>
      </div>
    </UCard>
  </div>
</template>
