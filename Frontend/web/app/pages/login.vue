<script setup lang="ts">
definePageMeta({
  layout: 'auth'
})

const { settings } = useUserSettings()
const { checkAuth } = useAuth()
const router = useRouter()
const checking = ref(true)
const authState = ref<'unknown' | 'authenticated' | 'unauthenticated'>('unknown')
const checkError = ref<string | null>(null)

const normalizeBaseUrl = (value: string) => {
  const trimmed = value.trim()
  if (!trimmed) return 'http://localhost:8080'
  return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed
}

const loginUrl = computed(() => `${normalizeBaseUrl(settings.value.backendBaseUrl)}/oauth2/authorization/google`)

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
  <div class="mx-auto mt-8 max-w-md">
    <UCard>
      <div class="space-y-6">
        <div class="space-y-2 text-center">
          <h1 class="text-2xl font-semibold">Login</h1>
          <p class="text-sm text-gray-600 dark:text-gray-400">
            Sign in with your Google account to continue.
          </p>
        </div>

        <div v-if="checking" class="space-y-2">
          <USkeleton class="h-11 w-full" />
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
            size="lg"
            label="Continue with Google"
          />

          <UAlert
            color="warning"
            variant="soft"
            icon="i-heroicons-exclamation-triangle"
            title="If you see 404 on OAuth URL"
            description="Backend is likely running with SPRING_PROFILES_ACTIVE=dev. Switch backend to production profile to enable /oauth2/authorization/google."
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

        <div class="rounded-lg bg-gray-50 p-3 text-xs text-gray-600 dark:bg-gray-900 dark:text-gray-300">
          <div class="font-medium">Backend</div>
          <code class="break-all">{{ normalizeBaseUrl(settings.backendBaseUrl) }}</code>
        </div>

        <p class="text-center text-xs text-gray-500 dark:text-gray-400">
          Legal:
          <NuxtLink to="/impressum" class="underline underline-offset-2">Impressum</NuxtLink>
          and
          <NuxtLink to="/datenschutz" class="underline underline-offset-2">Datenschutz</NuxtLink>.
        </p>
      </div>
    </UCard>
  </div>
</template>
