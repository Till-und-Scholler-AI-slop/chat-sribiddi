<script setup lang="ts">
definePageMeta({
  layout: 'default'
})

const { settings, hydrated, reset } = useUserSettings()
const { getStatus, saveKeys, deleteKeys } = useKeyApi()
const toast = useToast()

const saving = ref(false)
const loadingStatus = ref(true)
const status = ref<{ openai: boolean; google: boolean } | null>(null)

const form = reactive({
  openAiApiKey: '',
  googleApiKey: ''
})

onMounted(async () => {
  try {
    status.value = await getStatus()
  } catch (err) {
    console.error(err)
  } finally {
    loadingStatus.value = false
  }
})

async function handleSubmit() {
  saving.value = true
  try {
    await saveKeys({
      openAiApiKey: form.openAiApiKey || undefined,
      googleApiKey: form.googleApiKey || undefined
    })
    status.value = await getStatus()
    toast.add({
      title: 'Keys saved',
      description: 'Keys are stored in the backend for your account.',
      color: 'primary'
    })
    form.openAiApiKey = ''
    form.googleApiKey = ''
  } catch (err) {
    toast.add({
      title: 'Could not save keys',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    saving.value = false
  }
}

async function handleClearKeys() {
  saving.value = true
  try {
    await deleteKeys()
    status.value = await getStatus()
    toast.add({
      title: 'Keys removed',
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: 'Could not remove keys',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar title="Settings" />
    </template>

    <template #body>
      <UContainer class="py-6 space-y-6">
        <UAlert
          title="Backend-managed API keys"
          color="neutral"
          description="Model API keys are stored server-side after login. Frontend does not keep them."
          icon="i-heroicons-key"
        />

        <div v-if="!hydrated" class="space-y-3">
          <USkeleton class="h-12" />
          <USkeleton class="h-12" />
          <USkeleton class="h-12" />
        </div>

        <div v-else class="space-y-6">
          <UCard>
            <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
              <UFormGroup
                label="Backend base URL"
                description="The REST API from the Spring Boot backend."
                name="backendBaseUrl"
              >
                <UInput
                  v-model="settings.backendBaseUrl"
                  placeholder="http://localhost:8080"
                  icon="i-heroicons-globe-alt"
                />
              </UFormGroup>
            </div>
            <div class="mt-4">
              <UButton
                type="button"
                variant="ghost"
                icon="i-heroicons-rectangle-stack"
                label="Reset URL"
                @click="reset()"
              />
            </div>
          </UCard>

          <UCard>
            <div class="flex items-center justify-between gap-3 mb-4">
              <div class="space-y-1">
                <div class="font-semibold">Model API keys</div>
                <p class="text-sm text-gray-600 dark:text-gray-400">
                  Keys are stored per user in the backend. Submit only the keys you want to update.
                </p>
              </div>
              <div class="flex items-center gap-2">
                <UBadge
                  color="primary"
                  variant="soft"
                  :label="loadingStatus ? 'Checking…' : status?.openai ? 'OpenAI set' : 'OpenAI missing'"
                />
                <UBadge
                  color="primary"
                  variant="soft"
                  :label="loadingStatus ? 'Checking…' : status?.google ? 'Google set' : 'Google missing'"
                />
              </div>
            </div>

            <UForm @submit="handleSubmit" class="space-y-4">
              <UFormGroup
                label="OpenAI API key"
                description="Used when provider is OpenAI."
                name="openai"
              >
                <UInput
                  v-model="form.openAiApiKey"
                  type="password"
                  placeholder="sk-..."
                  icon="i-heroicons-lock-closed"
                  autocomplete="off"
                />
              </UFormGroup>

              <UFormGroup
                label="Google API key"
                description="Used when provider is Google Gemini."
                name="google"
              >
                <UInput
                  v-model="form.googleApiKey"
                  type="password"
                  placeholder="AIza..."
                  icon="i-heroicons-lock-closed"
                  autocomplete="off"
                />
              </UFormGroup>

              <div class="flex flex-wrap gap-3">
                <UButton
                  type="submit"
                  color="primary"
                  :loading="saving"
                  icon="i-heroicons-check-circle"
                  label="Save keys"
                />
                <UButton
                  type="button"
                  variant="outline"
                  color="neutral"
                  icon="i-heroicons-trash"
                  label="Clear stored keys"
                  :loading="saving"
                  @click="handleClearKeys"
                />
              </div>
            </UForm>
          </UCard>
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
