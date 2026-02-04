<script setup lang="ts">
definePageMeta({
  layout: 'default'
})

const { settings } = useUserSettings()
const { status, checkAuth } = useAuth()
const router = useRouter()
const toast = useToast()

const normalizeBaseUrl = (value: string) => {
  const trimmed = value.trim()
  if (!trimmed) return 'http://localhost:8080'
  return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed
}

const loginUrl = computed(() => `${normalizeBaseUrl(settings.value.backendBaseUrl)}/oauth2/authorization/google`)

onMounted(async () => {
  const state = await checkAuth()
  if (state === 'authenticated') {
    toast.add({ title: 'You are already signed in', color: 'primary' })
    router.push('/chat')
  }
})
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar title="Login" />
    </template>

    <template #body>
      <UContainer class="py-8">
        <div class="max-w-xl space-y-6">
          <UCard>
            <div class="space-y-4">
              <div class="space-y-2">
                <div class="text-lg font-semibold">Sign in with Google</div>
                <p class="text-sm text-gray-600 dark:text-gray-400">
                  Authentication is handled by the Spring backend. You will be redirected
                  to Google OAuth2. Make sure the backend URL matches your running instance.
                </p>
              </div>

              <div class="space-y-2 text-sm">
                <div class="flex items-center gap-2">
                  <UBadge color="primary" variant="soft">Backend</UBadge>
                  <span class="truncate">{{ normalizeBaseUrl(settings.backendBaseUrl) }}</span>
                </div>
                <div class="flex items-center gap-2">
                  <UBadge color="neutral" variant="soft">Endpoint</UBadge>
                  <code class="text-xs">{{ loginUrl }}</code>
                </div>
              </div>

              <div class="flex gap-3">
                <UButton
                  color="primary"
                  icon="i-heroicons-arrow-right-circle"
                  :href="loginUrl"
                  external
                  size="lg"
                  label="Continue with Google"
                />
                <UButton
                  color="neutral"
                  variant="outline"
                  icon="i-heroicons-cog-6-tooth"
                  to="/settings"
                  label="Edit backend URL"
                />
              </div>
            </div>
          </UCard>

          <UAlert
            color="neutral"
            icon="i-heroicons-information-circle"
            title="After login"
            description="You'll be sent back to the backend. Keep the same browser session and open the chat page to use the authenticated cookies."
          />
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
