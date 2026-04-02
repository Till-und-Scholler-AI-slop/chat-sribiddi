<script setup lang="ts">
import type {Provider} from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const { settings, hydrated} = useUserSettings()
const {getStatus, saveKey, deleteKey} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {deleteAccount} = useChatApi()
const {models} = useChatStore()
const {status: authStatus} = useAuth()
const toast = useToast()
const router = useRouter()

type KeyProviderStatus = {
  openai: boolean
  google: boolean
  claude: boolean
  openrouter: boolean
  brave_search: boolean
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
  },
  {
    provider: 'OPENROUTER',
    key: 'openrouter',
    title: 'OpenRouter',
    label: 'OpenRouter API key',
    placeholder: 'sk-or-...',
    description: 'Used to access the full OpenRouter model catalog.'
  }
]

const loadingStatus = ref(true)
const status = ref<KeyProviderStatus | null>(null)

const form = reactive<Record<Provider, string>>({
  OPENAI: '',
  GOOGLE: '',
  CLAUDE: '',
  OPENROUTER: ''
})

const reveal = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false,
  OPENROUTER: false
})

const savingProvider = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false,
  OPENROUTER: false
})

const deletingProvider = reactive<Record<Provider, boolean>>({
  OPENAI: false,
  GOOGLE: false,
  CLAUDE: false,
  OPENROUTER: false
})

const braveSearchKey = ref('')
const braveSearchReveal = ref(false)
const savingBraveSearch = ref(false)
const deletingBraveSearch = ref(false)

const braveSearchStatus = computed(() => status.value?.brave_search ?? false)

const providerDisplay = (provider: Provider) =>
    provider === 'OPENAI'
      ? 'OpenAI'
      : provider === 'GOOGLE'
        ? 'Google'
        : provider === 'CLAUDE'
          ? 'Claude'
          : 'OpenRouter'

const statusByProvider = (provider: Provider) => {
  if (!status.value) return false
  return provider === 'OPENAI'
      ? status.value.openai
      : provider === 'GOOGLE'
          ? status.value.google
          : provider === 'CLAUDE'
            ? status.value.claude
            : status.value.openrouter
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
  await Promise.allSettled([
    refreshStatus().catch(err => {
      console.error(err)
      toast.add({
        title: 'Could not load key status',
        description: err instanceof Error ? err.message : 'Backend not reachable.',
        color: 'error'
      })
    }),
    loadCustomInstructions()
  ])
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

async function handleSaveBraveSearch() {
  const key = braveSearchKey.value.trim()
  if (!key) {
    toast.add({
      title: 'Missing API key',
      description: 'Enter a Brave Search API key first.',
      color: 'warning'
    })
    return
  }

  savingBraveSearch.value = true
  try {
    await saveKey('BRAVE_SEARCH', key)
    braveSearchKey.value = ''
    await refreshStatus()
    toast.add({
      title: 'Brave Search key saved',
      description: 'The key is now stored in the backend for your account.',
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: 'Could not save Brave Search key',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    savingBraveSearch.value = false
  }
}

async function handleDeleteBraveSearch() {
  deletingBraveSearch.value = true
  try {
    await deleteKey('BRAVE_SEARCH')
    await refreshStatus()
    toast.add({
      title: 'Brave Search key removed',
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: 'Could not remove Brave Search key',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    deletingBraveSearch.value = false
  }
}

const showDeleteAccountModal = ref(false)
const deletingAccount = ref(false)

const customInstructions = ref('')
const loadingInstructions = ref(true)
const savingInstructions = ref(false)

async function loadCustomInstructions() {
  loadingInstructions.value = true
  try {
    const value = await getCustomInstructions()
    customInstructions.value = value ?? ''
  } catch (err) {
    console.error(err)
  } finally {
    loadingInstructions.value = false
  }
}

async function handleSaveInstructions() {
  savingInstructions.value = true
  try {
    await saveCustomInstructions(customInstructions.value.trim() || null)
    toast.add({
      title: 'Custom instructions saved',
      description: 'Your instructions will be appended to the system prompt.',
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: 'Could not save custom instructions',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    savingInstructions.value = false
  }
}

async function handleDeleteAccount() {
  deletingAccount.value = true
  try {
    await deleteAccount()
    showDeleteAccountModal.value = false
    authStatus.value = 'unauthenticated'
    models.value = []
    toast.add({
      title: 'Account deleted',
      description: 'Your account and all associated data have been permanently deleted.',
      color: 'neutral'
    })
    await router.push('/login')
  } catch (err) {
    toast.add({
      title: 'Could not delete account',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    deletingAccount.value = false
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
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
        </div>

        <div v-else class="space-y-8">
          <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-6 flex-wrap">
              <div class="space-y-1">
                <div class="text-lg font-semibold tracking-tight">Model API keys</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Save keys per provider. Each key is stored encrypted in the backend.
                </p>
              </div>
              <div class="flex items-center gap-2 flex-wrap">
                <UButton
                    to="/models"
                    color="neutral"
                    variant="outline"
                    size="sm"
                    icon="i-heroicons-cpu-chip"
                    label="Browse models"
                />
                <UBadge
                    v-for="item in providerCards"
                    :key="item.provider"
                    color="primary"
                    variant="soft"
                    :label="statusLabel(item.provider)"
                />
              </div>
            </div>

            <div class="grid grid-cols-1 gap-6 xl:grid-cols-2 stagger-children">
              <UCard v-for="item in providerCards" :key="item.provider" class="flex flex-col card-elevated">
                <div class="flex flex-col gap-5 h-full">
                  <div class="flex items-center justify-between gap-3">
                    <div class="flex items-center gap-2">
                      <span class="status-dot" :class="statusByProvider(item.provider) ? 'status-dot-active' : 'status-dot-inactive'" />
                      <div class="text-base font-semibold">{{ item.title }}</div>
                    </div>
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
                            class="btn-interactive"
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
        

          <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-6 flex-wrap">
              <div class="space-y-1">
                <div class="text-lg font-semibold tracking-tight">Web Search</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Configure the Brave Search API to enable web search in chat.
                  The AI can search the web when the toggle is activated in the chat prompt.
                </p>
              </div>
              <UBadge
                  color="primary"
                  variant="soft"
                  :label="braveSearchStatus ? 'Brave Search key set' : 'Brave Search key missing'"
              />
            </div>

            <UCard class="flex flex-col card-elevated">
              <div class="flex flex-col gap-5 h-full">
                <div class="flex items-center justify-between gap-3">
                  <div class="flex items-center gap-2">
                    <span class="status-dot" :class="braveSearchStatus ? 'status-dot-active' : 'status-dot-inactive'" />
                    <div class="text-base font-semibold">Brave Search</div>
                  </div>
                  <UBadge
                      variant="soft"
                      :color="braveSearchStatus ? 'primary' : 'neutral'"
                      :label="braveSearchStatus ? 'Stored' : 'Not stored'"
                  />
                </div>

                <UFormGroup
                    label="Brave Search API key"
                    description="Used for web search within chat conversations. Get your key at https://brave.com/search/api/."
                    name="brave_search"
                >
                  <UInput
                      v-model="braveSearchKey"
                      :type="braveSearchReveal ? 'text' : 'password'"
                      placeholder="BSA..."
                      icon="i-heroicons-lock-closed"
                      autocomplete="off"
                  />
                </UFormGroup>

                <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
                  <div class="flex items-center justify-between gap-3">
                    <div class="flex items-center gap-3">
                      <UButton
                          type="button"
                          variant="ghost"
                          color="neutral"
                          size="sm"
                          :icon="braveSearchReveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                          :label="braveSearchReveal ? 'Hide' : 'Show'"
                          @click="braveSearchReveal = !braveSearchReveal"
                      />
                      <UButton
                          type="button"
                          color="primary"
                          size="sm"
                          icon="i-heroicons-check-circle"
                          label="Save key"
                          class="btn-interactive"
                          :loading="savingBraveSearch"
                          :disabled="!braveSearchKey.trim() || savingBraveSearch || deletingBraveSearch"
                          @click="handleSaveBraveSearch"
                      />
                    </div>
                    <UButton
                        type="button"
                        variant="outline"
                        color="error"
                        size="sm"
                        icon="i-heroicons-trash"
                        label="Remove"
                        :loading="deletingBraveSearch"
                        :disabled="savingBraveSearch || deletingBraveSearch"
                        @click="handleDeleteBraveSearch"
                    />
                  </div>
                </div>
              </div>
            </UCard>
          </UCard>

          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <div class="text-lg font-semibold tracking-tight">Chat UI</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Customize optional chat helpers shown before you send a message.
                </p>
              </div>

              <UCheckbox
                v-model="settings.showPreSendCostEstimate"
                label="Show pre-send cost estimate"
                description="Displays a small estimated request cost next to the send button using the current chat context and model pricing."
              />
            </div>
          </UCard>

          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <div class="text-lg font-semibold tracking-tight">Custom instructions</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Add personal instructions that will be appended to the system prompt in every conversation. The base system prompt is always preserved.
                </p>
              </div>
              <div v-if="loadingInstructions" class="skeleton-shimmer h-28" />
              <div v-else class="space-y-3">
                <UTextarea
                    v-model="customInstructions"
                    placeholder="e.g. Always reply in German. Prefer concise bullet-point answers."
                    :rows="5"
                    :maxlength="4000"
                    autoresize
                />
                <div class="flex items-center justify-between gap-3">
                  <span class="text-xs text-gray-400">{{ customInstructions.length }}&thinsp;/&thinsp;4000</span>
                  <UButton
                      type="button"
                      color="primary"
                      size="sm"
                      icon="i-heroicons-check-circle"
                      label="Save instructions"
                      class="btn-interactive"
                      :loading="savingInstructions"
                      @click="handleSaveInstructions"
                  />
                </div>
              </div>
            </div>
          </UCard>

          <UCard class="border border-red-500/30 card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <div class="text-lg font-semibold tracking-tight text-red-500">Danger Zone</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Permanently delete your account and all associated data including chats, messages, and stored API keys. This action cannot be undone.
                </p>
              </div>
              <UButton
                  type="button"
                  color="error"
                  variant="outline"
                  icon="i-heroicons-trash"
                  label="Delete my account"
                  @click="showDeleteAccountModal = true"
              />
            </div>
          </UCard>

          <UModal v-model:open="showDeleteAccountModal" title="Delete account" description="This action is permanent and cannot be undone.">
            <template #body>
              <p class="text-sm text-gray-600 dark:text-gray-300">
                Are you sure you want to permanently delete your account? All your chats, messages, and stored API keys will be erased immediately.
              </p>
            </template>
            <template #footer>
              <div class="flex justify-end gap-3">
                <UButton
                    type="button"
                    variant="ghost"
                    color="neutral"
                    label="Cancel"
                    :disabled="deletingAccount"
                    @click="showDeleteAccountModal = false"
                />
                <UButton
                    type="button"
                    color="error"
                    icon="i-heroicons-trash"
                    label="Yes, delete my account"
                    :loading="deletingAccount"
                    @click="handleDeleteAccount"
                />
              </div>
            </template>
          </UModal>
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
