<script setup lang="ts">
import type { Provider } from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const { settings, hydrated, reset } = useUserSettings()
const { getStatus, saveKey, deleteKey } = useKeyApi()
const toast = useToast()

type KeyProviderStatus = {
  openai: boolean
  google: boolean
}

const loadingStatus = ref(true)
const status = ref<KeyProviderStatus | null>(null)

const form = reactive({
  openAiApiKey: '',
  googleApiKey: ''
})

const reveal = reactive({
  openai: false,
  google: false
})

const savingProvider = reactive({
  OPENAI: false,
  GOOGLE: false
})

const deletingProvider = reactive({
  OPENAI: false,
  GOOGLE: false
})

const statusLabel = (provider: Provider) => {
  if (loadingStatus.value) {
    return 'Checking...'
  }
  if (!status.value) {
    return 'Unknown'
  }

  return provider === 'OPENAI'
    ? status.value.openai ? 'OpenAI key set' : 'OpenAI key missing'
    : status.value.google ? 'Google key set' : 'Google key missing'
}

const inputValue = (provider: Provider) => {
  return provider === 'OPENAI' ? form.openAiApiKey : form.googleApiKey
}

const keyPresent = (provider: Provider) => {
  return inputValue(provider).trim().length > 0
}

const setInputValue = (provider: Provider, value: string) => {
  if (provider === 'OPENAI') {
    form.openAiApiKey = value
    return
  }
  form.googleApiKey = value
}

const providerDisplay = (provider: Provider) => provider === 'OPENAI' ? 'OpenAI' : 'Google'

const isProviderBusy = (provider: Provider) => {
  return savingProvider[provider] || deletingProvider[provider]
}

const refreshStatus = async () => {
  loadingStatus.value = true
  try {
    status.value = await getStatus()
  } finally {
    loadingStatus.value = false
  }
}

onMounted(async () => {
  try {
    await refreshStatus()
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Could not load key status',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  }
})

async function handleSave(provider: Provider) {
  const key = inputValue(provider).trim()
  if (!key) {
    toast.add({
      title: 'Missing API key',
      description: `Enter a ${providerDisplay(provider)} API key first.`,
      color: 'warning'
    })
    return
  }

  savingProvider[provider] = true
  try {
    await saveKey(provider, key)
    setInputValue(provider, '')
    await refreshStatus()
    toast.add({
      title: `${providerDisplay(provider)} key saved`,
      description: 'The key is now stored in the backend for your account.',
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: `Could not save ${providerDisplay(provider)} key`,
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    savingProvider[provider] = false
  }
}

async function handleDelete(provider: Provider) {
  deletingProvider[provider] = true
  try {
    await deleteKey(provider)
    await refreshStatus()
    toast.add({
      title: `${providerDisplay(provider)} key removed`,
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: `Could not remove ${providerDisplay(provider)} key`,
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    deletingProvider[provider] = false
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
                  Save keys per provider. Each key is stored encrypted in the backend.
                </p>
              </div>
              <div class="flex items-center gap-2">
                <UBadge
                  color="primary"
                  variant="soft"
                  :label="statusLabel('OPENAI')"
                />
                <UBadge
                  color="primary"
                  variant="soft"
                  :label="statusLabel('GOOGLE')"
                />
              </div>
            </div>

            <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
              <UCard>
                <div class="space-y-4">
                  <div class="flex items-center justify-between gap-2">
                    <div class="font-semibold">OpenAI</div>
                    <UBadge
                      variant="soft"
                      :color="status?.openai ? 'primary' : 'neutral'"
                      :label="status?.openai ? 'Stored' : 'Not stored'"
                    />
                  </div>

                  <UFormGroup
                    label="OpenAI API key"
                    description="Used for OpenAI models."
                    name="openai"
                  >
                    <UInput
                      v-model="form.openAiApiKey"
                      :type="reveal.openai ? 'text' : 'password'"
                      placeholder="sk-..."
                      icon="i-heroicons-lock-closed"
                      autocomplete="off"
                    />
                  </UFormGroup>

                  <div class="flex flex-wrap gap-2">
                    <UButton
                      type="button"
                      variant="ghost"
                      color="neutral"
                      :icon="reveal.openai ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                      :label="reveal.openai ? 'Hide key' : 'Show key'"
                      @click="reveal.openai = !reveal.openai"
                    />
                    <UButton
                      type="button"
                      color="primary"
                      icon="i-heroicons-check-circle"
                      label="Save key"
                      :loading="savingProvider.OPENAI"
                      :disabled="!keyPresent('OPENAI') || isProviderBusy('OPENAI')"
                      @click="handleSave('OPENAI')"
                    />
                    <UButton
                      type="button"
                      variant="outline"
                      color="neutral"
                      icon="i-heroicons-trash"
                      label="Remove stored key"
                      :loading="deletingProvider.OPENAI"
                      :disabled="isProviderBusy('OPENAI')"
                      @click="handleDelete('OPENAI')"
                    />
                  </div>
                </div>
              </UCard>

              <UCard>
                <div class="space-y-4">
                  <div class="flex items-center justify-between gap-2">
                    <div class="font-semibold">Google Gemini</div>
                    <UBadge
                      variant="soft"
                      :color="status?.google ? 'primary' : 'neutral'"
                      :label="status?.google ? 'Stored' : 'Not stored'"
                    />
                  </div>

                  <UFormGroup
                    label="Google API key"
                    description="Used for Google Gemini models."
                    name="google"
                  >
                    <UInput
                      v-model="form.googleApiKey"
                      :type="reveal.google ? 'text' : 'password'"
                      placeholder="AIza..."
                      icon="i-heroicons-lock-closed"
                      autocomplete="off"
                    />
                  </UFormGroup>

                  <UAlert
                    color="neutral"
                    variant="soft"
                    icon="i-heroicons-information-circle"
                    title="Google Project ID"
                    description="Configured in backend env (`GOOGLE_PROJECT_ID`). It is not part of the per-user key API contract."
                  />

                  <div class="flex flex-wrap gap-2">
                    <UButton
                      type="button"
                      variant="ghost"
                      color="neutral"
                      :icon="reveal.google ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                      :label="reveal.google ? 'Hide key' : 'Show key'"
                      @click="reveal.google = !reveal.google"
                    />
                    <UButton
                      type="button"
                      color="primary"
                      icon="i-heroicons-check-circle"
                      label="Save key"
                      :loading="savingProvider.GOOGLE"
                      :disabled="!keyPresent('GOOGLE') || isProviderBusy('GOOGLE')"
                      @click="handleSave('GOOGLE')"
                    />
                    <UButton
                      type="button"
                      variant="outline"
                      color="neutral"
                      icon="i-heroicons-trash"
                      label="Remove stored key"
                      :loading="deletingProvider.GOOGLE"
                      :disabled="isProviderBusy('GOOGLE')"
                      @click="handleDelete('GOOGLE')"
                    />
                  </div>
                </div>
              </UCard>
            </div>

            <div class="mt-4 text-xs text-gray-500 dark:text-gray-400">
              Backend contract in use: <code>PUT /apikeys/{provider}</code>,
              <code>DELETE /apikeys/{provider}</code>, <code>GET /apikeys/{provider}/exists</code>.
            </div>
          </UCard>
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
