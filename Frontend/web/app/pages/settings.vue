<script setup lang="ts">
import type {
  ConnectorProvider,
  ConnectorStatus,
  Provider,
  ToolCredentialProvider,
  ToolCredentialStatus
} from '~/composables/useChatApi'
import type { BillingMode } from '~/composables/useEntitlements'
import { providerLabel } from '~/utils/format'

const { t, locale, locales, setLocale } = useI18n()
const tt = useTranslatedError()

definePageMeta({
  layout: 'default'
})

const { settings, hydrated, saveBillingMode} = useUserSettings()
const {getStatus, saveKey, deleteKey} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {
  deleteAccount,
  fetchToolCredentialStatus,
  saveToolCredential,
  deleteToolCredential,
  fetchGithubOAuthUrl,
  fetchConnectors,
  fetchConnectorOAuthUrl,
  disconnectConnector
} = useChatApi()
const {entitlements, plan, billingModePreference, ensureLoaded, load: loadEntitlements} = useEntitlements()
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

type SettingsTab = 'keys' | 'instructions' | 'interface' | 'account'
const activeTab = ref<SettingsTab>('keys')
const tabItems = computed(() => [
  { label: t('settings.tabs.keys'), value: 'keys', icon: 'i-heroicons-key' },
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

const billingMode = ref<BillingMode>('AUTO')
const loadingBillingMode = ref(true)
const savingBillingMode = ref(false)

const isPaidPlan = computed(() => plan.value === 'PREMIUM' || plan.value === 'MAX')
const billingModeItems = computed(() => [
  {
    label: t('settings.billingModeAutoLabel'),
    value: 'AUTO' as BillingMode,
    description: t('settings.billingModeAutoDescription')
  },
  {
    label: t('settings.billingModeByokLabel'),
    value: 'BYOK' as BillingMode,
    description: t('settings.billingModeByokDescription')
  },
  {
    label: t('settings.billingModeManagedLabel'),
    value: 'MANAGED' as BillingMode,
    disabled: !isPaidPlan.value,
    description: isPaidPlan.value
      ? t('settings.billingModeManagedDescription')
      : t('settings.billingModeManagedUpgradeDescription')
  }
])

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

type ToolCredentialCard = {
  provider: ToolCredentialProvider
  key: ToolCredentialProvider
  titleKey: string
  descriptionKey: string
  placeholder: string
  icon: string
}

const toolCredentialForms = reactive({
  'openai-image': '',
  github: ''
})
const toolCredentialReveal = reactive({
  'openai-image': false,
  github: false
})
const toolCredentialStatuses = reactive<Record<ToolCredentialProvider, ToolCredentialStatus | null>>({
  'openai-image': null,
  github: null
})
const toolCredentialSaving = reactive<Record<ToolCredentialProvider, boolean>>({
  'openai-image': false,
  github: false
})
const toolCredentialDeleting = reactive<Record<ToolCredentialProvider, boolean>>({
  'openai-image': false,
  github: false
})
const githubConnecting = ref(false)

const connectorStatuses = reactive<Record<ConnectorProvider, ConnectorStatus | null>>({
  'google-drive': null,
  'google-gmail': null,
  'google-calendar': null
})
const connectorLoading = ref(false)
const connectorConnecting = reactive<Record<ConnectorProvider, boolean>>({
  'google-drive': false,
  'google-gmail': false,
  'google-calendar': false
})
const connectorDisconnecting = reactive<Record<ConnectorProvider, boolean>>({
  'google-drive': false,
  'google-gmail': false,
  'google-calendar': false
})

type IntegrationCategory = 'workspace' | 'research' | 'data' | 'developer' | 'creative'
type IntegrationCard = {
  id: string
  category: IntegrationCategory
  titleKey: string
  descriptionKey: string
  accessKey: string
  icon: string
  connector?: ConnectorProvider
  toolCredential?: ToolCredentialProvider
  kind: 'oauth' | 'api-key' | 'built-in'
  scopes?: string[]
  risk: 'LOW' | 'HIGH'
  plan: 'PREMIUM'
}

const integrationCards: IntegrationCard[] = [
  {
    id: 'google-drive',
    category: 'workspace',
    titleKey: 'settings.integrations.googleDriveTitle',
    descriptionKey: 'settings.integrations.googleDriveDescription',
    accessKey: 'settings.integrations.googleDriveAccess',
    icon: 'i-heroicons-folder-open',
    connector: 'google-drive',
    kind: 'oauth',
    risk: 'HIGH',
    plan: 'PREMIUM'
  },
  {
    id: 'google-gmail',
    category: 'workspace',
    titleKey: 'settings.integrations.gmailTitle',
    descriptionKey: 'settings.integrations.gmailDescription',
    accessKey: 'settings.integrations.gmailAccess',
    icon: 'i-heroicons-envelope',
    connector: 'google-gmail',
    kind: 'oauth',
    risk: 'HIGH',
    plan: 'PREMIUM'
  },
  {
    id: 'google-calendar',
    category: 'workspace',
    titleKey: 'settings.integrations.calendarTitle',
    descriptionKey: 'settings.integrations.calendarDescription',
    accessKey: 'settings.integrations.calendarAccess',
    icon: 'i-heroicons-calendar-days',
    connector: 'google-calendar',
    kind: 'oauth',
    risk: 'HIGH',
    plan: 'PREMIUM'
  },
  {
    id: 'web-research',
    category: 'research',
    titleKey: 'settings.integrations.webResearchTitle',
    descriptionKey: 'settings.integrations.webResearchDescription',
    accessKey: 'settings.integrations.webResearchAccess',
    icon: 'i-heroicons-globe-alt',
    kind: 'api-key',
    risk: 'HIGH',
    plan: 'PREMIUM'
  },
  {
    id: 'spreadsheet-analysis',
    category: 'data',
    titleKey: 'settings.integrations.spreadsheetTitle',
    descriptionKey: 'settings.integrations.spreadsheetDescription',
    accessKey: 'settings.integrations.spreadsheetAccess',
    icon: 'i-heroicons-table-cells',
    kind: 'built-in',
    risk: 'LOW',
    plan: 'PREMIUM'
  },
  {
    id: 'github',
    category: 'developer',
    titleKey: 'settings.integrations.githubTitle',
    descriptionKey: 'settings.integrations.githubDescription',
    accessKey: 'settings.integrations.githubAccess',
    icon: 'i-heroicons-code-bracket-square',
    toolCredential: 'github',
    kind: 'api-key',
    risk: 'HIGH',
    plan: 'PREMIUM'
  },
  {
    id: 'openai-image',
    category: 'creative',
    titleKey: 'settings.integrations.imageTitle',
    descriptionKey: 'settings.integrations.imageDescription',
    accessKey: 'settings.integrations.imageAccess',
    icon: 'i-heroicons-photo',
    toolCredential: 'openai-image',
    kind: 'api-key',
    risk: 'HIGH',
    plan: 'PREMIUM'
  }
]

const selectedIntegration = ref<IntegrationCard | null>(null)

const integrationGroups = computed(() =>
  (['workspace', 'research', 'data', 'developer', 'creative'] as IntegrationCategory[])
    .map(category => ({
      category,
      title: t(`settings.integrations.categories.${category}`),
      items: integrationCards.filter(item => item.category === category)
    }))
    .filter(group => group.items.length > 0)
)

const toolCredentialCards: ToolCredentialCard[] = [
  {
    provider: 'openai-image',
    key: 'openai-image',
    titleKey: 'settings.toolCredentials.imageTitle',
    descriptionKey: 'settings.toolCredentials.imageDescription',
    placeholder: 'sk-...',
    icon: 'i-heroicons-photo'
  },
  {
    provider: 'github',
    key: 'github',
    titleKey: 'settings.toolCredentials.githubTitle',
    descriptionKey: 'settings.toolCredentials.githubDescription',
    placeholder: 'github_pat_...',
    icon: 'i-heroicons-code-bracket-square'
  }
]

const isToolCredentialConfigured = (provider: ToolCredentialProvider) => {
  const current = toolCredentialStatuses[provider]
  return current?.userConfigured === true || current?.managedConfigured === true
}

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
        title: t('settings.loadKeyStatusFailed'),
        description: tt(err),
        color: 'error'
      })
    }),
    refreshToolCredentialStatuses(),
    refreshConnectors(),
    loadBillingModePreference(),
    loadCustomInstructions()
  ])
})

function isBillingMode(value: unknown): value is BillingMode {
  return value === 'AUTO' || value === 'BYOK' || value === 'MANAGED'
}

async function loadBillingModePreference() {
  loadingBillingMode.value = true
  try {
    const loaded = await ensureLoaded()
    billingMode.value = loaded?.billingModePreference ?? billingModePreference.value
  } catch (err) {
    console.error(err)
  } finally {
    loadingBillingMode.value = false
  }
}

async function handleBillingModeChange(next: unknown) {
  if (!isBillingMode(next) || next === billingMode.value) return

  if (next === 'MANAGED' && !isPaidPlan.value) {
    toast.add({
      title: t('settings.billingModeManagedUnavailableTitle'),
      description: t('settings.billingModeManagedUpgradeDescription'),
      color: 'warning'
    })
    return
  }

  const previous = billingMode.value
  billingMode.value = next
  savingBillingMode.value = true
  try {
    const result = await saveBillingMode(next)
    billingMode.value = result.billingMode
    models.value = []
    await loadEntitlements({ force: true })
    toast.add({
      title: t('settings.billingModeSavedTitle'),
      description: t('settings.billingModeSavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    billingMode.value = previous
    toast.add({
      title: t('settings.billingModeSaveFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    savingBillingMode.value = false
  }
}

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

async function refreshToolCredentialStatuses() {
  await Promise.all(
    toolCredentialCards.map(async item => {
      try {
        toolCredentialStatuses[item.provider] = await fetchToolCredentialStatus(item.provider)
      } catch (err) {
        console.error(err)
        toolCredentialStatuses[item.provider] = null
      }
    })
  )
}

async function refreshConnectors() {
  connectorLoading.value = true
  try {
    const connectors = await fetchConnectors()
    for (const connector of connectors) {
      connectorStatuses[connector.provider] = connector
    }
  } catch (err) {
    console.error(err)
  } finally {
    connectorLoading.value = false
  }
}

function integrationStatus(item: IntegrationCard) {
  if (item.connector) {
    return connectorStatuses[item.connector]?.status ?? 'NOT_CONNECTED'
  }
  if (item.toolCredential) {
    return isToolCredentialConfigured(item.toolCredential) ? 'CONNECTED' : 'NOT_CONNECTED'
  }
  if (item.id === 'web-research') {
    return braveSearchStatus.value ? 'CONNECTED' : 'NOT_CONNECTED'
  }
  return 'CONNECTED'
}

function integrationBadgeColor(item: IntegrationCard) {
  const status = integrationStatus(item)
  if (status === 'CONNECTED') return 'primary'
  if (status === 'NEEDS_RECONNECT') return 'warning'
  if (status === 'UNAVAILABLE') return 'error'
  return 'neutral'
}

function integrationStatusLabel(item: IntegrationCard) {
  return t(`settings.integrations.status.${integrationStatus(item).toLowerCase()}`)
}

function integrationScopes(item: IntegrationCard) {
  if (item.connector) {
    return connectorStatuses[item.connector]?.scopes ?? []
  }
  if (item.id === 'web-research') return ['Brave Search API key']
  if (item.id === 'spreadsheet-analysis') return [t('settings.integrations.localOnly')]
  if (item.id === 'github') return ['GitHub read-only search token']
  if (item.id === 'openai-image') return ['OpenAI Images API key']
  return item.scopes ?? []
}

function integrationLastUsed(item: IntegrationCard) {
  if (!item.connector) return null
  const value = connectorStatuses[item.connector]?.lastUsedAt
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleString()
}

function showIntegrationDetails(item: IntegrationCard) {
  selectedIntegration.value = item
}

async function handleConnectIntegration(item: IntegrationCard) {
  if (item.connector) {
    connectorConnecting[item.connector] = true
    try {
      const response = await fetchConnectorOAuthUrl(item.connector)
      if (import.meta.client) {
        window.location.href = response.url
      }
    } catch (err) {
      toast.add({
        title: t('settings.integrations.connectFailed'),
        description: tt(err),
        color: 'error'
      })
    } finally {
      connectorConnecting[item.connector] = false
    }
    return
  }
  if (item.id === 'github') {
    await handleGithubConnect()
    return
  }
  showIntegrationDetails(item)
}

async function handleDisconnectIntegration(item: IntegrationCard) {
  if (!item.connector) {
    if (item.toolCredential) {
      await handleDeleteToolCredential(item.toolCredential)
    }
    return
  }
  connectorDisconnecting[item.connector] = true
  try {
    await disconnectConnector(item.connector)
    await refreshConnectors()
    toast.add({
      title: t('settings.integrations.disconnectedTitle'),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.integrations.disconnectFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    connectorDisconnecting[item.connector] = false
  }
}

function integrationBusy(item: IntegrationCard) {
  if (!item.connector) return false
  return connectorConnecting[item.connector] || connectorDisconnecting[item.connector]
}

async function handleSaveToolCredential(provider: ToolCredentialProvider) {
  const secret = toolCredentialForms[provider].trim()
  if (!secret) {
    toast.add({
      title: t('settings.missingKeyTitle'),
      description: t('settings.toolCredentials.missingSecret'),
      color: 'warning'
    })
    return
  }

  toolCredentialSaving[provider] = true
  try {
    await saveToolCredential(provider, secret)
    toolCredentialForms[provider] = ''
    await refreshToolCredentialStatuses()
    toast.add({
      title: t('settings.toolCredentials.savedTitle'),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.toolCredentials.saveFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    toolCredentialSaving[provider] = false
  }
}

async function handleDeleteToolCredential(provider: ToolCredentialProvider) {
  toolCredentialDeleting[provider] = true
  try {
    await deleteToolCredential(provider)
    await refreshToolCredentialStatuses()
    toast.add({
      title: t('settings.toolCredentials.removedTitle'),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.toolCredentials.removeFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    toolCredentialDeleting[provider] = false
  }
}

async function handleGithubConnect() {
  githubConnecting.value = true
  try {
    const response = await fetchGithubOAuthUrl()
    if (import.meta.client) {
      window.location.href = response.url
    }
  } catch (err) {
    toast.add({
      title: t('settings.toolCredentials.githubConnectFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    githubConnecting.value = false
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
            <template v-if="activeTab === 'keys'">
              <UCard class="card-elevated">
                <div class="space-y-4">
                  <div class="space-y-1">
                    <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">{{ t('settings.billingModeHeading') }}</h2>
                    <p class="text-sm text-gray-500 dark:text-gray-400 max-w-2xl">
                      {{ t('settings.billingModeDescription') }}
                    </p>
                  </div>
                  <div v-if="loadingBillingMode" class="skeleton-shimmer h-24" />
                  <URadioGroup
                    v-else
                    :model-value="billingMode"
                    :items="billingModeItems"
                    value-key="value"
                    label-key="label"
                    description-key="description"
                    color="primary"
                    :disabled="savingBillingMode"
                    @update:model-value="handleBillingModeChange"
                  />
                </div>
              </UCard>

              <UCard class="card-elevated">
                <div class="flex items-start justify-between gap-4 mb-8 flex-wrap">
                  <div class="space-y-1">
                    <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                      {{ t('settings.integrations.heading') }}
                    </h2>
                    <p class="text-sm text-gray-500 dark:text-gray-400 max-w-2xl">
                      {{ t('settings.integrations.description') }}
                    </p>
                  </div>
                  <UButton
                    type="button"
                    color="neutral"
                    variant="outline"
                    size="sm"
                    icon="i-heroicons-arrow-path"
                    :label="t('common.refresh')"
                    :loading="connectorLoading"
                    @click="Promise.all([refreshConnectors(), refreshToolCredentialStatuses(), refreshStatus()])"
                  />
                </div>

                <div class="space-y-7">
                  <section
                    v-for="group in integrationGroups"
                    :key="group.category"
                    class="space-y-3"
                  >
                    <h3 class="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                      {{ group.title }}
                    </h3>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div
                        v-for="item in group.items"
                        :key="item.id"
                        class="rounded-lg border border-gray-200 dark:border-gray-800 bg-white/60 dark:bg-gray-950/40 p-4 space-y-4"
                      >
                        <div class="flex items-start justify-between gap-3">
                          <div class="flex items-start gap-3 min-w-0">
                            <span class="inline-flex size-9 items-center justify-center rounded-md bg-gray-100 dark:bg-gray-900 text-gray-600 dark:text-gray-300 shrink-0">
                              <UIcon :name="item.icon" class="size-5" />
                            </span>
                            <div class="min-w-0">
                              <div class="font-semibold text-gray-900 dark:text-gray-50">
                                {{ t(item.titleKey) }}
                              </div>
                              <p class="text-sm text-gray-500 dark:text-gray-400">
                                {{ t(item.descriptionKey) }}
                              </p>
                            </div>
                          </div>
                          <UBadge
                            variant="soft"
                            :color="integrationBadgeColor(item)"
                            :label="integrationStatusLabel(item)"
                          />
                        </div>

                        <div class="text-sm text-gray-600 dark:text-gray-300">
                          {{ t(item.accessKey) }}
                        </div>

                        <div class="flex items-center justify-between gap-3 flex-wrap">
                          <div class="flex items-center gap-2 flex-wrap">
                            <UBadge variant="subtle" color="neutral" :label="item.plan" />
                            <UBadge
                              variant="subtle"
                              :color="item.risk === 'HIGH' ? 'warning' : 'neutral'"
                              :label="t(`settings.integrations.risk.${item.risk.toLowerCase()}`)"
                            />
                          </div>
                          <div class="flex items-center gap-2">
                            <UButton
                              type="button"
                              color="neutral"
                              variant="ghost"
                              size="sm"
                              icon="i-heroicons-information-circle"
                              :label="t('settings.integrations.manage')"
                              @click="showIntegrationDetails(item)"
                            />
                            <UButton
                              v-if="integrationStatus(item) === 'CONNECTED'"
                              type="button"
                              color="error"
                              variant="outline"
                              size="sm"
                              icon="i-heroicons-x-mark"
                              :label="t('settings.integrations.disconnect')"
                              :loading="integrationBusy(item)"
                              @click="handleDisconnectIntegration(item)"
                            />
                            <UButton
                              v-else-if="item.kind !== 'built-in'"
                              type="button"
                              color="primary"
                              variant="solid"
                              size="sm"
                              icon="i-heroicons-link"
                              :label="t('settings.integrations.connect')"
                              :loading="integrationBusy(item)"
                              :disabled="integrationStatus(item) === 'UNAVAILABLE'"
                              @click="handleConnectIntegration(item)"
                            />
                          </div>
                        </div>
                      </div>
                    </div>
                  </section>
                </div>
              </UCard>

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

          <UCard class="card-elevated">
            <div class="flex items-start justify-between gap-4 mb-8 flex-wrap">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                  {{ t('settings.toolCredentials.heading') }}
                </h2>
                <p class="text-sm text-gray-500 dark:text-gray-400 max-w-xl">
                  {{ t('settings.toolCredentials.description') }}
                </p>
              </div>
            </div>

            <div class="grid grid-cols-1 gap-6">
              <UCard
                v-for="item in toolCredentialCards"
                :key="item.provider"
                class="flex flex-col card-elevated"
              >
                <div class="flex flex-col gap-5 h-full">
                  <div class="flex items-center justify-between gap-3 flex-wrap">
                    <div class="flex items-center gap-2">
                      <UIcon :name="item.icon" class="size-5 text-gray-500" />
                      <span
                        class="status-dot"
                        :class="isToolCredentialConfigured(item.provider) ? 'status-dot-active' : 'status-dot-inactive'"
                      />
                      <div class="text-base font-semibold">{{ t(item.titleKey) }}</div>
                    </div>
                    <UBadge
                      variant="soft"
                      :color="isToolCredentialConfigured(item.provider) ? 'primary' : 'neutral'"
                      :label="isToolCredentialConfigured(item.provider) ? t('settings.stored') : t('settings.notStored')"
                    />
                  </div>

                  <UFormField
                    :label="t('settings.toolCredentials.secretLabel')"
                    :description="t(item.descriptionKey)"
                    :name="`tool-${item.provider}`"
                  >
                    <UInput
                      v-model="toolCredentialForms[item.provider]"
                      :type="toolCredentialReveal[item.provider] ? 'text' : 'password'"
                      :placeholder="item.placeholder"
                      icon="i-heroicons-lock-closed"
                      autocomplete="off"
                      class="w-full"
                    />
                  </UFormField>

                  <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
                    <div class="flex items-center justify-between gap-3 flex-wrap">
                      <div class="flex items-center gap-3 flex-wrap">
                        <UButton
                          type="button"
                          variant="ghost"
                          color="neutral"
                          size="sm"
                          :icon="toolCredentialReveal[item.provider] ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                          :label="toolCredentialReveal[item.provider] ? t('common.hide') : t('common.show')"
                          @click="toolCredentialReveal[item.provider] = !toolCredentialReveal[item.provider]"
                        />
                        <UButton
                          type="button"
                          color="primary"
                          size="sm"
                          icon="i-heroicons-check-circle"
                          :label="t('settings.saveKey')"
                          :loading="toolCredentialSaving[item.provider]"
                          :disabled="!toolCredentialForms[item.provider].trim() || toolCredentialSaving[item.provider] || toolCredentialDeleting[item.provider]"
                          @click="handleSaveToolCredential(item.provider)"
                        />
                        <UButton
                          v-if="item.provider === 'github'"
                          type="button"
                          color="neutral"
                          variant="outline"
                          size="sm"
                          icon="i-heroicons-arrow-top-right-on-square"
                          :label="t('settings.toolCredentials.connectGithub')"
                          :loading="githubConnecting"
                          @click="handleGithubConnect"
                        />
                      </div>
                      <UButton
                        type="button"
                        variant="outline"
                        color="error"
                        size="sm"
                        icon="i-heroicons-trash"
                        :label="t('common.remove')"
                        :loading="toolCredentialDeleting[item.provider]"
                        :disabled="toolCredentialSaving[item.provider] || toolCredentialDeleting[item.provider]"
                        @click="handleDeleteToolCredential(item.provider)"
                      />
                    </div>
                  </div>
                </div>
              </UCard>
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

          <UModal
            :open="!!selectedIntegration"
            :title="selectedIntegration ? t(selectedIntegration.titleKey) : ''"
            :description="selectedIntegration ? t(selectedIntegration.descriptionKey) : ''"
            @update:open="value => { if (!value) selectedIntegration = null }"
          >
            <template #body>
              <div v-if="selectedIntegration" class="space-y-5">
                <div class="rounded-lg border border-gray-200 dark:border-gray-800 p-4 space-y-2">
                  <div class="text-sm font-medium text-gray-900 dark:text-gray-50">
                    {{ t('settings.integrations.accessHeading') }}
                  </div>
                  <p class="text-sm text-gray-600 dark:text-gray-300">
                    {{ t(selectedIntegration.accessKey) }}
                  </p>
                </div>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
                  <div>
                    <div class="text-gray-500 dark:text-gray-400">{{ t('settings.integrations.statusLabel') }}</div>
                    <div class="font-medium">{{ integrationStatusLabel(selectedIntegration) }}</div>
                  </div>
                  <div>
                    <div class="text-gray-500 dark:text-gray-400">{{ t('settings.integrations.credentialType') }}</div>
                    <div class="font-medium">{{ selectedIntegration.kind }}</div>
                  </div>
                  <div>
                    <div class="text-gray-500 dark:text-gray-400">{{ t('settings.integrations.riskLabel') }}</div>
                    <div class="font-medium">{{ t(`settings.integrations.risk.${selectedIntegration.risk.toLowerCase()}`) }}</div>
                  </div>
                  <div>
                    <div class="text-gray-500 dark:text-gray-400">{{ t('settings.integrations.lastUsed') }}</div>
                    <div class="font-medium">{{ integrationLastUsed(selectedIntegration) || '—' }}</div>
                  </div>
                </div>
                <div>
                  <div class="text-sm font-medium text-gray-900 dark:text-gray-50 mb-2">
                    {{ t('settings.integrations.scopesHeading') }}
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <UBadge
                      v-for="scope in integrationScopes(selectedIntegration)"
                      :key="scope"
                      variant="subtle"
                      color="neutral"
                      :label="scope"
                    />
                  </div>
                </div>
              </div>
            </template>
            <template #footer>
              <div class="flex justify-end gap-3">
                <UButton
                  type="button"
                  variant="ghost"
                  color="neutral"
                  :label="t('common.close')"
                  @click="selectedIntegration = null"
                />
                <UButton
                  v-if="selectedIntegration && integrationStatus(selectedIntegration) === 'CONNECTED'"
                  type="button"
                  variant="outline"
                  color="error"
                  icon="i-heroicons-x-mark"
                  :label="t('settings.integrations.disconnect')"
                  :loading="selectedIntegration ? integrationBusy(selectedIntegration) : false"
                  @click="selectedIntegration && handleDisconnectIntegration(selectedIntegration)"
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
