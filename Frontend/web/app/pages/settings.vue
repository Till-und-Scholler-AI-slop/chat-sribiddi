<script setup lang="ts">
import type {Provider} from '~/composables/useChatApi'
import type {
  ProviderAuthMethod,
  ProviderCatalogItem,
  ProviderConnectionStatus,
  SaveProviderCredentialInput
} from '~/composables/useKeyApi'
import { providerLabel } from '~/utils/format'

const { t, locale, locales, setLocale } = useI18n()
const tt = useTranslatedError()

definePageMeta({
  layout: 'default'
})

const { settings, hydrated } = useUserSettings()
const {getProviders, saveKey, saveProviderCredential, deleteKey, startConnection} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {deleteAccount} = useChatApi()
const {models, ensureModels} = useChatStore()
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
  titleKey: string
  labelKey: string
  placeholder: string
  descriptionKey: string
}

const providerCards: ProviderCard[] = [
  {
    provider: 'OPENAI',
    key: 'openai',
    titleKey: 'settings.openaiTitle',
    labelKey: 'settings.openaiLabel',
    placeholder: 'sk-...',
    descriptionKey: 'settings.openaiDescription'
  },
  {
    provider: 'GOOGLE',
    key: 'google',
    titleKey: 'settings.googleTitle',
    labelKey: 'settings.googleLabel',
    placeholder: 'AIza...',
    descriptionKey: 'settings.googleDescription'
  },
  {
    provider: 'CLAUDE',
    key: 'claude',
    titleKey: 'settings.claudeTitle',
    labelKey: 'settings.claudeLabel',
    placeholder: 'sk-ant-...',
    descriptionKey: 'settings.claudeDescription'
  },
  {
    provider: 'OPENROUTER',
    key: 'openrouter',
    titleKey: 'settings.openrouterTitle',
    labelKey: 'settings.openrouterLabel',
    placeholder: 'sk-or-...',
    descriptionKey: 'settings.openrouterDescription'
  }
]

const localeItems = computed(() =>
  (locales.value as Array<{ code: string; name?: string }>).map(l => ({
    label: l.name || l.code,
    value: l.code
  }))
)

type SettingsTab = 'connections' | 'instructions' | 'interface' | 'account'
const activeTab = ref<SettingsTab>('connections')
const tabItems = computed(() => [
  { label: t('settings.tabs.keys'), value: 'connections', icon: 'i-heroicons-key' },
  { label: t('settings.tabs.instructions'), value: 'instructions', icon: 'i-heroicons-document-text' },
  { label: t('settings.tabs.interface'), value: 'interface', icon: 'i-heroicons-paint-brush' },
  { label: t('settings.tabs.account'), value: 'account', icon: 'i-heroicons-user-circle' }
])

const handleLocaleChange = async (next: string) => {
  if (!next || next === locale.value) return
  await setLocale(next as 'de' | 'en')
}

// ── Color mode (light / dark / system) ─────────────────────────────────
const colorMode = useColorMode()

type ColorModePreference = 'light' | 'dark' | 'system'

const colorModePreference = computed<ColorModePreference>({
  get: () => (colorMode.preference as ColorModePreference) || 'system',
  set: (value: ColorModePreference) => {
    colorMode.preference = value
  }
})

const colorModeItems = computed(() => [
  { label: t('settings.colorModeLight'), value: 'light' as const },
  { label: t('settings.colorModeDark'), value: 'dark' as const },
  { label: t('settings.colorModeSystem'), value: 'system' as const }
])

const handleColorModeChange = (next: ColorModePreference) => {
  if (!next) return
  colorModePreference.value = next
}

const expertModeLoading = ref(false)

const handleExpertModeChange = async (next: boolean | 'indeterminate') => {
  settings.value.expertMode = next === true
  expertModeLoading.value = true
  try {
    await ensureModels()
  } catch (err) {
    toast.add({
      title: t('models.loadCatalogFailed'),
      description: tt(err, 'models.backendNotReachable'),
      color: 'error'
    })
  } finally {
    expertModeLoading.value = false
  }
}

const loadingStatus = ref(true)
const status = ref<KeyProviderStatus | null>(null)
const providerCatalog = ref<ProviderCatalogItem[]>([])
const extraConnectionForms = reactive<Record<string, {
  apiKey: string
  baseUrl: string
  modelId: string
  reveal: boolean
  saving: boolean
}>>({})

const legacyProviderIds: Record<Provider | 'BRAVE_SEARCH', string> = {
  OPENAI: 'openai',
  GOOGLE: 'google',
  CLAUDE: 'anthropic',
  OPENROUTER: 'openrouter',
  BRAVE_SEARCH: 'brave-search'
}

const catalogById = computed(() => new Map(providerCatalog.value.map(provider => [provider.id, provider])))

const extraProviderCatalog = computed(() => {
  const hidden = new Set(Object.values(legacyProviderIds))
  return providerCatalog.value.filter(provider => !hidden.has(provider.id))
})

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

const connectingProvider = reactive<Record<Provider, boolean>>({
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

const providerCatalogEntry = (provider: Provider | 'BRAVE_SEARCH') =>
  catalogById.value.get(legacyProviderIds[provider])

const providerAuthMethods = (provider: Provider | 'BRAVE_SEARCH') =>
  providerCatalogEntry(provider)?.authMethods ?? ['API_KEY']

const authMethodLabel = (method: ProviderAuthMethod) => {
  const key = `settings.authMethods.${method}`
  const translated = t(key)
  return translated === key ? method.replaceAll('_', ' ') : translated
}

const statusLabel = (value: ProviderConnectionStatus) => {
  const key = `settings.connectionStatuses.${value}`
  const translated = t(key)
  return translated === key ? value.replaceAll('_', ' ') : translated
}

const statusColor = (value: ProviderConnectionStatus) => {
  if (value === 'READY') return 'primary'
  if (value === 'EXPERIMENTAL') return 'warning'
  if (value === 'DISABLED' || value === 'ERROR') return 'error'
  return 'neutral'
}

const extraForm = (providerId: string) => {
  if (!extraConnectionForms[providerId]) {
    extraConnectionForms[providerId] = {
      apiKey: '',
      baseUrl: '',
      modelId: '',
      reveal: false,
      saving: false
    }
  }
  return extraConnectionForms[providerId]
}

const extraProviderMethod = (provider: ProviderCatalogItem): ProviderAuthMethod => {
  if (provider.authMethods.includes('LOCAL_ENDPOINT')) return 'LOCAL_ENDPOINT'
  if (provider.authMethods.includes('OPENAI_COMPATIBLE')) return 'OPENAI_COMPATIBLE'
  return 'API_KEY'
}

const canConfigureExtraProvider = (provider: ProviderCatalogItem) => {
  if (provider.experimental || provider.status === 'DISABLED') return false
  return provider.authMethods.some(method =>
    ['API_KEY', 'OPENAI_COMPATIBLE', 'LOCAL_ENDPOINT'].includes(method)
  )
}

const needsEndpoint = (provider: ProviderCatalogItem) =>
  provider.authMethods.includes('OPENAI_COMPATIBLE') || provider.authMethods.includes('LOCAL_ENDPOINT')

const keyPresent = (provider: Provider) => form[provider].trim().length > 0

const isProviderBusy = (provider: Provider) => {
  return savingProvider[provider] || deletingProvider[provider] || connectingProvider[provider]
}

const refreshStatus = async () => {
  loadingStatus.value = true
  try {
    const providers = await getProviders()
    providerCatalog.value = providers
    const ready = (id: string) => providers.find(provider => provider.id === id)?.status === 'READY'
    status.value = {
      openai: ready('openai'),
      google: ready('google'),
      claude: ready('anthropic'),
      openrouter: ready('openrouter'),
      brave_search: ready('brave-search')
    }
  } finally {
    loadingStatus.value = false
  }
}

onMounted(async () => {
  await Promise.allSettled([
    refreshStatus().catch(err => {
      console.error(err)
      toast.add({
        title: t('settings.loadKeyStatusFailed'),
        description: tt(err),
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
      title: t('settings.missingKeyTitle'),
      description: t('settings.missingKeyDescription', { provider: providerLabel(provider) }),
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
      title: t('settings.keySavedTitle', { provider: providerLabel(provider) }),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.keySaveFailed', { provider: providerLabel(provider) }),
      description: tt(err),
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
      title: t('settings.keyRemovedTitle', { provider: providerLabel(provider) }),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.keyRemoveFailed', { provider: providerLabel(provider) }),
      description: tt(err),
      color: 'error'
    })
  } finally {
    deletingProvider[provider] = false
  }
}

async function handleConnect(provider: Provider, authMethod: ProviderAuthMethod) {
  connectingProvider[provider] = true
  try {
    const response = await startConnection(provider, authMethod)
    if (response?.mode === 'REDIRECT' && response.authorizationUrl) {
      window.location.assign(response.authorizationUrl)
      return
    }
    toast.add({
      title: t('settings.connectionUnavailableTitle'),
      description: response?.message || t('settings.connectionUnavailableDescription'),
      color: 'warning'
    })
  } catch (err) {
    toast.add({
      title: t('settings.connectionStartFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    connectingProvider[provider] = false
  }
}

async function handleSaveBraveSearch() {
  const key = braveSearchKey.value.trim()
  if (!key) {
    toast.add({
      title: t('settings.missingKeyTitle'),
      description: t('settings.missingBraveKeyDescription'),
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
      title: t('settings.braveSavedTitle'),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.braveSaveFailed'),
      description: tt(err),
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
      title: t('settings.braveRemovedTitle'),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.braveRemoveFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    deletingBraveSearch.value = false
  }
}

async function handleSaveExtraProvider(provider: ProviderCatalogItem) {
  const form = extraForm(provider.id)
  const method = extraProviderMethod(provider)
  const apiKey = form.apiKey.trim()
  const baseUrl = form.baseUrl.trim()
  const modelId = form.modelId.trim()
  if (needsEndpoint(provider) && !baseUrl) {
    toast.add({
      title: t('settings.missingEndpointTitle'),
      description: t('settings.missingEndpointDescription'),
      color: 'warning'
    })
    return
  }
  if (method === 'API_KEY' && !apiKey) {
    toast.add({
      title: t('settings.missingKeyTitle'),
      description: t('settings.missingKeyDescription', { provider: provider.label }),
      color: 'warning'
    })
    return
  }
  if (!modelId) {
    toast.add({
      title: t('settings.missingModelIdTitle'),
      description: t('settings.missingModelIdDescription'),
      color: 'warning'
    })
    return
  }

  form.saving = true
  try {
    const payload: SaveProviderCredentialInput = {
      authMethod: method,
      apiKey: apiKey || undefined,
      baseUrl: baseUrl || undefined,
      defaultModelId: modelId,
      displayName: provider.label
    }
    await saveProviderCredential(provider.id, payload)
    form.apiKey = ''
    form.modelId = ''
    await refreshStatus()
    toast.add({
      title: t('settings.connectionSavedTitle', { provider: provider.label }),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.connectionSaveFailed', { provider: provider.label }),
      description: tt(err),
      color: 'error'
    })
  } finally {
    form.saving = false
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
      title: t('settings.customInstructionsSavedTitle'),
      description: t('settings.customInstructionsSavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.customInstructionsSaveFailed'),
      description: tt(err),
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
      title: t('settings.accountDeletedTitle'),
      description: t('settings.accountDeletedDescription'),
      color: 'neutral'
    })
    await router.push('/login')
  } catch (err) {
    toast.add({
      title: t('settings.deleteAccountFailed'),
      description: tt(err),
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
            {{ t('settings.title') }}
          </h1>
          <p class="text-base text-gray-500 dark:text-gray-400 max-w-2xl">
            {{ t('settings.pageIntro') }}
          </p>
        </header>

        <div v-if="!hydrated" class="space-y-4">
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
          <div class="skeleton-shimmer h-14"/>
        </div>

        <div v-else>
          <div class="overflow-x-auto">
            <UTabs
              v-model="activeTab"
              :items="tabItems"
              :content="false"
              color="primary"
              variant="link"
              class="w-full"
            />
          </div>

          <div class="space-y-8 mt-8">
            <template v-if="activeTab === 'connections'">
              <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-8 flex-wrap">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.modelKeysHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400 max-w-xl">
                  {{ t('settings.modelKeysDescription') }}
                </p>
              </div>
              <UButton
                  to="/models"
                  color="neutral"
                  variant="outline"
                  size="sm"
                  icon="i-heroicons-cpu-chip"
                  :label="t('settings.browseModels')"
              />
            </div>

            <div class="grid grid-cols-1 gap-6 stagger-children">
              <UCard v-for="item in providerCards" :key="item.provider" class="flex flex-col card-elevated">
                <div class="flex flex-col gap-5 h-full">
                  <div class="flex items-center justify-between gap-3 flex-wrap">
                    <div class="flex items-center gap-2">
                      <span class="status-dot" :class="statusByProvider(item.provider) ? 'status-dot-active' : 'status-dot-inactive'" />
                      <div class="text-base font-semibold">{{ t(item.titleKey) }}</div>
                    </div>
                    <UBadge
                        variant="soft"
                        :color="statusByProvider(item.provider) ? 'primary' : 'neutral'"
                        :label="statusByProvider(item.provider) ? t('settings.stored') : t('settings.notStored')"
                    />
                  </div>

                  <UFormField
                      :label="t(item.labelKey)"
                      :description="t(item.descriptionKey)"
                      :name="item.provider.toLowerCase()"
                  >
                    <UInput
                        v-model="form[item.provider]"
                        :type="reveal[item.provider] ? 'text' : 'password'"
                        :placeholder="item.placeholder"
                        icon="i-heroicons-lock-closed"
                        autocomplete="off"
                        class="w-full"
                    />
                  </UFormField>

                  <div class="flex items-center gap-2 flex-wrap">
                    <UBadge
                      v-for="method in providerAuthMethods(item.provider)"
                      :key="`${item.provider}-${method}`"
                      variant="soft"
                      color="neutral"
                      :label="authMethodLabel(method)"
                    />
                  </div>

                  <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
                    <div class="flex items-center justify-between gap-3 flex-wrap">
                      <div class="flex items-center gap-3 flex-wrap">
                        <UButton
                            type="button"
                            variant="ghost"
                            color="neutral"
                            size="sm"
                            :icon="reveal[item.provider] ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                            :label="reveal[item.provider] ? t('common.hide') : t('common.show')"
                            @click="reveal[item.provider] = !reveal[item.provider]"
                        />
                        <UButton
                            type="button"
                            color="primary"
                            size="sm"
                            icon="i-heroicons-check-circle"
                            :label="t('settings.saveKey')"
                            class="btn-interactive"
                            :loading="savingProvider[item.provider]"
                            :disabled="!keyPresent(item.provider) || isProviderBusy(item.provider)"
                            @click="handleSave(item.provider)"
                        />
                        <UButton
                            v-if="item.provider === 'OPENROUTER'"
                            type="button"
                            variant="outline"
                            color="neutral"
                            size="sm"
                            icon="i-heroicons-link"
                            :label="t('settings.connectProvider')"
                            :loading="connectingProvider[item.provider]"
                            :disabled="isProviderBusy(item.provider)"
                            @click="handleConnect(item.provider, 'OAUTH_PKCE')"
                        />
                        <UButton
                            v-if="item.provider === 'GOOGLE'"
                            type="button"
                            variant="outline"
                            color="neutral"
                            size="sm"
                            icon="i-heroicons-link"
                            :label="t('settings.connectProvider')"
                            :loading="connectingProvider[item.provider]"
                            :disabled="isProviderBusy(item.provider)"
                            @click="handleConnect(item.provider, 'OAUTH_AUTH_CODE')"
                        />
                      </div>
                      <UButton
                          type="button"
                          variant="outline"
                          color="error"
                          size="sm"
                          icon="i-heroicons-trash"
                          :label="t('common.remove')"
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
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.openClawCatalogHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400 max-w-xl">
                  {{ t('settings.openClawCatalogDescription') }}
                </p>
              </div>
              <UBadge
                color="neutral"
                variant="soft"
                :label="t('settings.providersCount', { count: providerCatalog.length })"
              />
            </div>

            <div v-if="loadingStatus" class="space-y-3">
              <div class="skeleton-shimmer h-12" />
              <div class="skeleton-shimmer h-12" />
              <div class="skeleton-shimmer h-12" />
            </div>
            <div v-else class="max-h-[32rem] overflow-y-auto divide-y divide-gray-200 dark:divide-gray-800 rounded-md border border-gray-200 dark:border-gray-800">
              <div
                v-for="provider in extraProviderCatalog"
                :key="provider.id"
                class="grid grid-cols-1 gap-3 px-4 py-3 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,28rem)] lg:items-start"
              >
                <div class="min-w-0 space-y-2">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-medium text-gray-900 dark:text-gray-50">{{ provider.label }}</span>
                    <UBadge
                      v-if="provider.experimental"
                      color="warning"
                      variant="soft"
                      :label="t('settings.experimental')"
                    />
                    <UBadge
                      v-if="provider.localOnly"
                      color="neutral"
                      variant="soft"
                      :label="t('settings.localOnly')"
                    />
                    <UBadge
                      :color="statusColor(provider.status)"
                      variant="soft"
                      :label="statusLabel(provider.status)"
                    />
                  </div>
                  <div class="flex items-center gap-2 flex-wrap">
                    <UBadge
                      v-for="method in provider.authMethods"
                      :key="`${provider.id}-${method}`"
                      color="neutral"
                      variant="soft"
                      :label="authMethodLabel(method)"
                    />
                  </div>
                </div>

                <div v-if="canConfigureExtraProvider(provider)" class="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_auto]">
                  <div class="grid grid-cols-1 gap-2">
                    <UInput
                      v-if="needsEndpoint(provider)"
                      v-model="extraForm(provider.id).baseUrl"
                      :placeholder="provider.localOnly ? 'http://127.0.0.1:1234/v1' : 'https://api.provider.com/v1'"
                      icon="i-heroicons-link"
                      autocomplete="off"
                      class="w-full"
                      :aria-label="t('settings.endpointUrlLabel')"
                    />
                    <UInput
                      v-model="extraForm(provider.id).modelId"
                      :placeholder="t('settings.gatewayModelIdPlaceholder')"
                      icon="i-heroicons-cpu-chip"
                      autocomplete="off"
                      class="w-full"
                      :aria-label="t('settings.gatewayModelIdLabel')"
                    />
                    <UInput
                      v-model="extraForm(provider.id).apiKey"
                      :type="extraForm(provider.id).reveal ? 'text' : 'password'"
                      :placeholder="t('settings.gatewayApiKeyPlaceholder')"
                      icon="i-heroicons-lock-closed"
                      autocomplete="off"
                      class="w-full"
                      :aria-label="t('settings.gatewayApiKeyLabel')"
                    />
                  </div>
                  <div class="flex items-center gap-2 sm:flex-col sm:items-stretch">
                    <UButton
                      type="button"
                      variant="ghost"
                      color="neutral"
                      size="sm"
                      :icon="extraForm(provider.id).reveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                      :label="extraForm(provider.id).reveal ? t('common.hide') : t('common.show')"
                      @click="extraForm(provider.id).reveal = !extraForm(provider.id).reveal"
                    />
                    <UButton
                      type="button"
                      color="primary"
                      size="sm"
                      icon="i-heroicons-check-circle"
                      :label="t('settings.saveConnection')"
                      :loading="extraForm(provider.id).saving"
                      :disabled="extraForm(provider.id).saving"
                      @click="handleSaveExtraProvider(provider)"
                    />
                  </div>
                </div>
              </div>
            </div>
          </UCard>


          <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-8 flex-wrap">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.webSearchHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400 max-w-xl">
                  {{ t('settings.webSearchDescription') }}
                </p>
              </div>
              <UBadge
                  color="primary"
                  variant="soft"
                  :label="braveSearchStatus ? t('settings.braveStatusKeySet') : t('settings.braveStatusKeyMissing')"
              />
            </div>

            <div class="flex flex-col gap-5">
              <div class="flex items-center justify-between gap-3 flex-wrap">
                <div class="flex items-center gap-2">
                  <span class="status-dot" :class="braveSearchStatus ? 'status-dot-active' : 'status-dot-inactive'" />
                  <div class="text-base font-semibold">{{ t('settings.braveTitle') }}</div>
                </div>
                <UBadge
                    variant="soft"
                    :color="braveSearchStatus ? 'primary' : 'neutral'"
                    :label="braveSearchStatus ? t('settings.stored') : t('settings.notStored')"
                />
              </div>

              <UFormField
                  :label="t('settings.braveLabel')"
                  :description="t('settings.braveDescription')"
                  name="brave_search"
              >
                <UInput
                    v-model="braveSearchKey"
                    :type="braveSearchReveal ? 'text' : 'password'"
                    placeholder="BSA..."
                    icon="i-heroicons-lock-closed"
                    autocomplete="off"
                    class="w-full"
                />
              </UFormField>

              <div class="pt-4 border-t border-gray-200 dark:border-gray-800">
                <div class="flex items-center justify-between gap-3 flex-wrap">
                  <div class="flex items-center gap-3 flex-wrap">
                    <UButton
                        type="button"
                        variant="ghost"
                        color="neutral"
                        size="sm"
                        :icon="braveSearchReveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                        :label="braveSearchReveal ? t('common.hide') : t('common.show')"
                        @click="braveSearchReveal = !braveSearchReveal"
                    />
                    <UButton
                        type="button"
                        color="primary"
                        size="sm"
                        icon="i-heroicons-check-circle"
                        :label="t('settings.saveKey')"
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
                      :label="t('common.remove')"
                      :loading="deletingBraveSearch"
                      :disabled="savingBraveSearch || deletingBraveSearch"
                      @click="handleDeleteBraveSearch"
                  />
                </div>
              </div>
            </div>
          </UCard>
            </template>

            <template v-if="activeTab === 'interface'">
          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.colorModeHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('settings.colorModeDescription') }}
                </p>
              </div>
              <USelect
                :model-value="colorModePreference"
                :items="colorModeItems"
                value-key="value"
                label-key="label"
                icon="i-heroicons-swatch"
                class="max-w-xs"
                :aria-label="t('settings.colorModeAria')"
                @update:model-value="handleColorModeChange"
              />
            </div>
          </UCard>

          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.languageHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('settings.languageDescription') }}
                </p>
              </div>
              <USelect
                :model-value="locale"
                :items="localeItems"
                value-key="value"
                label-key="label"
                icon="i-heroicons-language"
                class="max-w-xs"
                :aria-label="t('language.switchAria')"
                @update:model-value="handleLocaleChange"
              />
            </div>
          </UCard>

          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.chatUiHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('settings.chatUiDescription') }}
                </p>
              </div>

              <UCheckbox
                v-model="settings.showPreSendCostEstimate"
                :label="t('settings.preSendCostLabel')"
                :description="t('settings.preSendCostDescription')"
              />
              <UCheckbox
                :model-value="settings.expertMode"
                :label="t('settings.expertModeLabel')"
                :description="t('settings.expertModeDescription')"
                :disabled="expertModeLoading"
                @update:model-value="handleExpertModeChange"
              />
            </div>
          </UCard>
            </template>

            <template v-if="activeTab === 'instructions'">
          <UCard class="card-elevated">
            <div class="space-y-6">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.customInstructionsHeading') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400 max-w-2xl">
                  {{ t('settings.customInstructionsDescription') }}
                </p>
              </div>
              <div v-if="loadingInstructions" class="skeleton-shimmer h-64" />
              <div v-else class="space-y-4">
                <UTextarea
                    v-model="customInstructions"
                    :placeholder="t('settings.customInstructionsPlaceholder')"
                    :rows="14"
                    :maxlength="4000"
                    autoresize
                    class="w-full"
                    :ui="{ base: 'w-full min-h-[20rem] resize-y leading-relaxed' }"
                />
                <div class="flex items-center justify-between gap-3 flex-wrap">
                  <span class="text-xs text-gray-400 tabular-nums">{{ customInstructions.length }}&thinsp;/&thinsp;4000</span>
                  <UButton
                      type="button"
                      color="primary"
                      size="sm"
                      icon="i-heroicons-check-circle"
                      :label="t('settings.customInstructionsSave')"
                      class="btn-interactive"
                      :loading="savingInstructions"
                      @click="handleSaveInstructions"
                  />
                </div>
              </div>
            </div>
          </UCard>
            </template>

            <template v-if="activeTab === 'account'">
          <UCard class="border border-red-500/30 card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-red-500">{{ t('settings.dangerZone') }}</h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('settings.dangerZoneDescription') }}
                </p>
              </div>
              <UButton
                  type="button"
                  color="error"
                  variant="outline"
                  icon="i-heroicons-trash"
                  :label="t('settings.deleteAccount')"
                  @click="showDeleteAccountModal = true"
              />
            </div>
          </UCard>
            </template>

          </div>

          <UModal v-model:open="showDeleteAccountModal" :title="t('settings.deleteAccountModalTitle')" :description="t('settings.deleteAccountModalDescription')">
            <template #body>
              <p class="text-sm text-gray-600 dark:text-gray-300">
                {{ t('settings.deleteAccountConfirm') }}
              </p>
            </template>
            <template #footer>
              <div class="flex justify-end gap-3">
                <UButton
                    type="button"
                    variant="ghost"
                    color="neutral"
                    :label="t('common.cancel')"
                    :disabled="deletingAccount"
                    @click="showDeleteAccountModal = false"
                />
                <UButton
                    type="button"
                    color="error"
                    icon="i-heroicons-trash"
                    :label="t('settings.deleteAccountConfirmAction')"
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
