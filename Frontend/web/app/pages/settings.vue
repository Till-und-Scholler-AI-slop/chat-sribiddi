<script setup lang="ts">
import type {Provider} from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const { hydrated} = useUserSettings()
const {getStatus, saveKey, deleteKey} = useKeyApi()
const {models} = useChatStore()
const toast = useToast()

type KeyProviderStatus = {
  openai: boolean
  google: boolean
  claude: boolean
}

type ProviderCard = {
  provider: Provider
  key: keyof KeyProviderStatus
  title: string
  label: string
  placeholder: string
  description: string
}

const providerCards: ProviderCard[] = [
  {
    provider: 'OPENAI',
    key: 'openai',
    title: 'OpenAI',
    label: 'OpenAI API key',
    placeholder: 'sk-...',
    description: 'Used for OpenAI models.'
  },
  {
    provider: 'GOOGLE',
    key: 'google',
    title: 'Google Gemini',
    label: 'Google API key',
    placeholder: 'AIza...',
    description: 'Used for Google Gemini models.'
  },
  {
    provider: 'CLAUDE',
    key: 'claude',
    title: 'Claude (Anthropic)',
    label: 'Claude API key',
    placeholder: 'sk-ant-...',
    description: 'Used for Claude models.'
  }
]

const loadingStatus = ref(true)
const status = ref<KeyProviderStatus | null>(null)

const form = reactive<Record<Provider, string>>({
  OPENAI: '',
  GOOGLE: '',
  CLAUDE: ''
})

const reveal = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false
})

const savingProvider = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false
})

const deletingProvider = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false
})

const providerDisplay = (provider: Provider) =>
    provider === 'OPENAI' ? 'OpenAI' : provider === 'GOOGLE' ? 'Google' : 'Claude'

const statusByProvider = (provider: Provider) => {
  if (!status.value) return false
  return provider === 'OPENAI'
      ? status.value.openai
      : provider === 'GOOGLE'
          ? status.value.google
          : status.value.claude
}

const statusLabel = (provider: Provider) => {
  if (loadingStatus.value) {
    return `${providerDisplay(provider)} status loading...`
  }
  return statusByProvider(provider)
      ? `${providerDisplay(provider)} key set`
      : `${providerDisplay(provider)} key missing`
}

const keyPresent = (provider: Provider) => form[provider].trim().length > 0

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
  const key = form[provider].trim()
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
    form[provider] = ''
    models.value = []
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
    models.value = []
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
      <UDashboardNavbar title="Settings"/>
    </template>

    <template #body>
      <UContainer class="py-8 space-y-8">

        <div v-if="!hydrated" class="space-y-4">
          <USkeleton class="h-14"/>
          <USkeleton class="h-14"/>
          <USkeleton class="h-14"/>
        </div>

        <div v-else class="space-y-8">
          <UCard>
            <div class="flex items-start justify-between gap-4 mb-6 flex-wrap">
              <div class="space-y-1">
                <div class="text-lg font-semibold">Model API keys</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Save keys per provider. Each key is stored encrypted in the backend.
                </p>
              </div>
              <div class="flex items-center gap-2 flex-wrap">
                <UBadge
                    v-for="item in providerCards"
                    :key="item.provider"
                    color="primary"
                    variant="soft"
                    :label="statusLabel(item.provider)"
                />
              </div>
            </div>

            <div class="grid grid-cols-1 gap-6 xl:grid-cols-2">
              <UCard v-for="item in providerCards" :key="item.provider" class="flex flex-col">
                <div class="flex flex-col gap-5 h-full">
                  <div class="flex items-center justify-between gap-3">
                    <div class="text-base font-semibold">{{ item.title }}</div>
                    <UBadge
                        variant="soft"
                        :color="statusByProvider(item.provider) ? 'primary' : 'neutral'"
                        :label="statusByProvider(item.provider) ? 'Stored' : 'Not stored'"
                    />
                  </div>

                  <UFormGroup
                      :label="item.label"
                      :description="item.description"
                      :name="item.provider.toLowerCase()"
                  >
                    <UInput
                        v-model="form[item.provider]"
                        :type="reveal[item.provider] ? 'text' : 'password'"
                        :placeholder="item.placeholder"
                        icon="i-heroicons-lock-closed"
                        autocomplete="off"
                    />
                  </UFormGroup>

                  <div v-if="item.provider === 'GOOGLE'">
                    <UAlert
                        color="neutral"
                        variant="soft"
                        icon="i-heroicons-information-circle"
                        title="Google Project ID"
                        description="Configured in backend env (`GOOGLE_PROJECT_ID`). It is not part of the per-user key API contract."
                    />
                  </div>

                  <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
                    <div class="flex items-center justify-between gap-3">
                      <div class="flex items-center gap-3">
                        <UButton
                            type="button"
                            variant="ghost"
                            color="neutral"
                            size="sm"
                            :icon="reveal[item.provider] ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                            :label="reveal[item.provider] ? 'Hide' : 'Show'"
                            @click="reveal[item.provider] = !reveal[item.provider]"
                        />
                        <UButton
                            type="button"
                            color="primary"
                            size="sm"
                            icon="i-heroicons-check-circle"
                            label="Save key"
                            :loading="savingProvider[item.provider]"
                            :disabled="!keyPresent(item.provider) || isProviderBusy(item.provider)"
                            @click="handleSave(item.provider)"
                        />
                      </div>
                      <UButton
                          type="button"
                          variant="outline"
                          color="error"
                          size="sm"
                          icon="i-heroicons-trash"
                          label="Remove"
                          :loading="deletingProvider[item.provider]"
                          :disabled="isProviderBusy(item.provider)"
                          @click="handleDelete(item.provider)"
                      />
                    </div>
                  </div>
                </div>
              </UCard>
            </div>
          </UCard>
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
