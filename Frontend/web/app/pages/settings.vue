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
    return `${providerDisplay(provider)} Status wird geladen…`
  }
  return statusByProvider(provider)
      ? `${providerDisplay(provider)}-Schlüssel hinterlegt`
      : `${providerDisplay(provider)}-Schlüssel fehlt`
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
  <div>
  <UDashboardPanel>
    <template #body>
      <UContainer class="py-10 max-w-4xl space-y-10">

        <!-- Page heading -->
        <header class="space-y-3">
          <h1 class="font-serif-display text-4xl sm:text-5xl text-gray-900 dark:text-gray-50">
            Einstellungen
          </h1>
          <p class="text-base text-gray-500 dark:text-gray-400 max-w-2xl">
            Verwalte deine API-Schlüssel, Chat-Optionen und Kontodaten.
          </p>
        </header>

        <div v-if="!hydrated" class="space-y-4">
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
        </div>

        <div v-else class="space-y-8">
          <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-6 flex-wrap">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">API-Schlüssel</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Speichere pro Anbieter einen Schlüssel. Jeder Schlüssel wird verschlüsselt im Backend abgelegt.
                </p>
              </div>
              <div class="flex items-center gap-2 flex-wrap">
                <UButton
                    to="/models"
                    color="neutral"
                    variant="outline"
                    size="sm"
                    icon="i-heroicons-cpu-chip"
                    label="Alle Modelle ansehen"
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
                        :label="statusByProvider(item.provider) ? 'Hinterlegt' : 'Nicht hinterlegt'"
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

                  <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
                    <div class="flex items-center justify-between gap-3">
                      <div class="flex items-center gap-3">
                        <UButton
                            type="button"
                            variant="ghost"
                            color="neutral"
                            size="sm"
                            :icon="reveal[item.provider] ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                            :label="reveal[item.provider] ? 'Verbergen' : 'Anzeigen'"
                            @click="reveal[item.provider] = !reveal[item.provider]"
                        />
                        <UButton
                            type="button"
                            color="primary"
                            size="sm"
                            icon="i-heroicons-check-circle"
                            label="Schlüssel speichern"
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
                          label="Entfernen"
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
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">Web-Suche</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Konfiguriere die Brave-Search-API, um die Web-Suche im Chat zu aktivieren.
                  Die KI kann das Web durchsuchen, wenn der Schalter im Chat-Prompt aktiviert ist.
                </p>
              </div>
              <UBadge
                  color="primary"
                  variant="soft"
                  :label="braveSearchStatus ? 'Brave-Search-Schlüssel gesetzt' : 'Brave-Search-Schlüssel fehlt'"
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
                      :label="braveSearchStatus ? 'Hinterlegt' : 'Nicht hinterlegt'"
                  />
                </div>

                <UFormGroup
                    label="Brave-Search-API-Schlüssel"
                    description="Wird für die Web-Suche in Chats verwendet. Schlüssel erhältst du unter https://brave.com/search/api/."
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
                          :label="braveSearchReveal ? 'Verbergen' : 'Anzeigen'"
                          @click="braveSearchReveal = !braveSearchReveal"
                      />
                      <UButton
                          type="button"
                          color="primary"
                          size="sm"
                          icon="i-heroicons-check-circle"
                          label="Schlüssel speichern"
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
                        label="Entfernen"
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
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">Chat-Oberfläche</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Passe optionale Chat-Hilfen an, die vor dem Senden einer Nachricht angezeigt werden.
                </p>
              </div>

              <UCheckbox
                v-model="settings.showPreSendCostEstimate"
                label="Kosten-Voranschlag vor dem Senden anzeigen"
                description="Zeigt die geschätzten Kosten der Anfrage neben dem Senden-Button anhand des aktuellen Chat-Kontexts und der Modellpreise."
              />
            </div>
          </UCard>

          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">Eigene Anweisungen</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Hinterlege persönliche Anweisungen, die in jedem Chat an den System-Prompt angehängt werden. Der Basis-System-Prompt bleibt erhalten.
                </p>
              </div>
              <div v-if="loadingInstructions" class="skeleton-shimmer h-28" />
              <div v-else class="space-y-3">
                <UTextarea
                    v-model="customInstructions"
                    placeholder="z. B. Antworte immer auf Deutsch. Bevorzuge knappe Antworten in Stichpunkten."
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
                      label="Anweisungen speichern"
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
                <h2 class="font-serif-display text-2xl text-red-500">Gefahrenzone</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  Lösche dein Konto und alle zugehörigen Daten dauerhaft, einschließlich Chats, Nachrichten und gespeicherter API-Schlüssel. Diese Aktion kann nicht rückgängig gemacht werden.
                </p>
              </div>
              <UButton
                  type="button"
                  color="error"
                  variant="outline"
                  icon="i-heroicons-trash"
                  label="Konto löschen"
                  @click="showDeleteAccountModal = true"
              />
            </div>
          </UCard>

          <UModal v-model:open="showDeleteAccountModal" title="Konto löschen" description="Diese Aktion ist permanent und kann nicht rückgängig gemacht werden.">
            <template #body>
              <p class="text-sm text-gray-600 dark:text-gray-300">
                Möchtest du dein Konto wirklich dauerhaft löschen? Alle Chats, Nachrichten und gespeicherten API-Schlüssel werden sofort entfernt.
              </p>
            </template>
            <template #footer>
              <div class="flex justify-end gap-3">
                <UButton
                    type="button"
                    variant="ghost"
                    color="neutral"
                    label="Abbrechen"
                    :disabled="deletingAccount"
                    @click="showDeleteAccountModal = false"
                />
                <UButton
                    type="button"
                    color="error"
                    icon="i-heroicons-trash"
                    label="Ja, Konto löschen"
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
  </div>
</template>
