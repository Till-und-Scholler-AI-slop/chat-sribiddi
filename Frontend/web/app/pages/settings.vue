<script setup lang="ts">
import type {
  ProviderAuthMethod,
  ProviderCatalogItem,
  ProviderConnectionProfile,
  ProviderConnectionSummary,
  ProviderConnectionStatus,
  SaveProviderCredentialInput
} from '~/composables/useKeyApi'

const { t, locale, locales, setLocale } = useI18n()
const tt = useTranslatedError()

definePageMeta({
  layout: 'default'
})

const { settings } = useUserSettings()
const {
  getProviders,
  saveProviderCredential,
  startConnection,
  completeDeviceConnection,
  deleteConnection
} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {deleteAccount} = useChatApi()
const {models, ensureModels} = useChatStore()
const {status: authStatus} = useAuth()
const toast = useToast()
const router = useRouter()

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
const settingsPageReady = ref(false)
const providerCatalog = ref<ProviderCatalogItem[]>([])

type ProviderFormState = {
  selectedProfileId: string
  apiKey: string
  baseUrl: string
  modelId: string
  reveal: boolean
  advanced: boolean
  saving: boolean
  connecting: boolean
  deletingConnectionId: number | null
}

type DeviceFlowState = {
  providerId: string
  providerLabel: string
  connectionProfileId: string
  deviceCode: string
  userCode: string
  verificationUri: string
  verificationUriComplete?: string | null
  expiresAt?: string | null
  intervalSeconds: number
  message?: string | null
  polling: boolean
}

const providerForms = reactive<Record<string, ProviderFormState>>({})
const deviceFlow = ref<DeviceFlowState | null>(null)
let devicePollToken = 0

const authMethodLabel = (method: ProviderAuthMethod) => {
  const key = `settings.authMethods.${method}`
  const translated = t(key)
  return translated === key ? method.replaceAll('_', ' ') : translated
}

const connectionStatusLabel = (provider: ProviderCatalogItem, profile: ProviderConnectionProfile) => {
  const connection = connectionForProfile(provider, profile)
  return connection?.status ? statusLabel(connection.status) : statusLabel(profile.status)
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

const profileStatusColor = (provider: ProviderCatalogItem, profile: ProviderConnectionProfile) => {
  const connection = connectionForProfile(provider, profile)
  return statusColor(connection?.status ?? profile.status)
}

const usableProfiles = (provider: ProviderCatalogItem) =>
  provider.connectionProfiles.filter(profile => profile.status !== 'DISABLED')

const primaryProfile = (provider: ProviderCatalogItem) =>
  usableProfiles(provider)[0] ?? provider.connectionProfiles[0]

const profileItems = (provider: ProviderCatalogItem) =>
  provider.connectionProfiles.map(profile => ({
    label: profile.label,
    value: profile.id,
    disabled: profile.status === 'DISABLED'
  }))

const providerForm = (provider: ProviderCatalogItem) => {
  if (!providerForms[provider.id]) {
    const profile = primaryProfile(provider)
    providerForms[provider.id] = {
      selectedProfileId: profile?.id ?? '',
      apiKey: '',
      baseUrl: profile?.defaultBaseUrl ?? '',
      modelId: connectionForProfile(provider, profile)?.defaultModelId ?? '',
      reveal: false,
      advanced: false,
      saving: false,
      connecting: false,
      deletingConnectionId: null
    }
  }
  return providerForms[provider.id]
}

const selectedProfile = (provider: ProviderCatalogItem) => {
  const form = providerForm(provider)
  return (
    provider.connectionProfiles.find(profile => profile.id === form.selectedProfileId) ??
    primaryProfile(provider)
  )
}

const connectionForProfile = (
  provider: ProviderCatalogItem,
  profile: ProviderConnectionProfile | undefined
) => {
  if (!profile) return undefined
  return provider.connections.find(connection => connection.connectionProfileId === profile.id)
}

const providerReady = (provider: ProviderCatalogItem) =>
  provider.connections.some(connection => connection.status === 'READY')

const isProfileConfigurable = (profile: ProviderConnectionProfile | undefined) =>
  Boolean(
    profile &&
      profile.status !== 'DISABLED' &&
      (profile.requiresApiKey ||
        profile.requiresModelId ||
        profile.baseUrlEditable ||
        profile.defaultBaseUrl)
  )

const needsEndpointOverride = (profile: ProviderConnectionProfile | undefined) =>
  Boolean(profile?.baseUrlEditable || profile?.defaultBaseUrl)

const saveDisabled = (provider: ProviderCatalogItem) => {
  const form = providerForm(provider)
  const profile = selectedProfile(provider)
  if (!isProfileConfigurable(profile) || form.saving || form.connecting || form.deletingConnectionId) return true
  if (profile?.requiresApiKey && !form.apiKey.trim()) return true
  if (profile?.requiresModelId && !form.modelId.trim()) return true
  if (needsEndpointOverride(profile) && !form.baseUrl.trim()) return true
  return false
}

const connectLabel = (profile: ProviderConnectionProfile | undefined) =>
  profile?.authMethod === 'DEVICE_CODE' ? t('settings.connectDevice') : t('settings.connectOAuth')

const syncProviderForms = (providers: ProviderCatalogItem[]) => {
  for (const provider of providers) {
    const form = providerForm(provider)
    const current = provider.connectionProfiles.find(profile => profile.id === form.selectedProfileId)
    const profile = current ?? primaryProfile(provider)
    if (profile && !current) {
      form.selectedProfileId = profile.id
    }
    const connection = connectionForProfile(provider, profile)
    if (!form.baseUrl && profile?.defaultBaseUrl) {
      form.baseUrl = profile.defaultBaseUrl
    }
    if (!form.modelId && connection?.defaultModelId) {
      form.modelId = connection.defaultModelId
    }
  }
}

const handleProfileChange = (provider: ProviderCatalogItem, nextProfileId: string) => {
  const form = providerForm(provider)
  const profile = provider.connectionProfiles.find(item => item.id === nextProfileId)
  if (!profile) return
  form.selectedProfileId = profile.id
  form.baseUrl = profile.defaultBaseUrl ?? ''
  form.modelId = connectionForProfile(provider, profile)?.defaultModelId ?? ''
  form.apiKey = ''
  form.advanced = false
}

const refreshStatus = async () => {
  loadingStatus.value = true
  try {
    const providers = await getProviders()
    providerCatalog.value = providers
    syncProviderForms(providers)
  } finally {
    loadingStatus.value = false
  }
}

onMounted(async () => {
  settingsPageReady.value = true
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

onUnmounted(() => {
  devicePollToken += 1
})

async function handleSaveProvider(provider: ProviderCatalogItem) {
  const form = providerForm(provider)
  const profile = selectedProfile(provider)
  if (!profile) return

  const apiKey = form.apiKey.trim()
  const baseUrl = form.baseUrl.trim()
  const modelId = form.modelId.trim()
  if (profile.requiresApiKey && !apiKey) {
    toast.add({
      title: t('settings.missingKeyTitle'),
      description: t('settings.missingKeyDescription', { provider: provider.label }),
      color: 'warning'
    })
    return
  }
  if (needsEndpointOverride(profile) && !baseUrl) {
    toast.add({
      title: t('settings.missingEndpointTitle'),
      description: t('settings.missingEndpointDescription'),
      color: 'warning'
    })
    return
  }
  if (profile.requiresModelId && !modelId) {
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
      connectionProfileId: profile.id,
      authMethod: profile.authMethod,
      apiKey: apiKey || undefined,
      baseUrl: baseUrl || undefined,
      defaultModelId: modelId || undefined,
      displayName: `${provider.label} (${profile.label})`
    }
    await saveProviderCredential(provider.id, payload)
    form.apiKey = ''
    models.value = []
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

async function handleStartConnection(provider: ProviderCatalogItem) {
  const form = providerForm(provider)
  const profile = selectedProfile(provider)
  if (!profile) return
  form.connecting = true
  try {
    const response = await startConnection(provider.id, profile.authMethod, profile.id)
    if (response?.mode === 'REDIRECT' && response.authorizationUrl) {
      window.location.assign(response.authorizationUrl)
      return
    }
    if (
      response?.mode === 'DEVICE_CODE' &&
      response.deviceCode &&
      response.userCode &&
      response.verificationUri
    ) {
      devicePollToken += 1
      const token = devicePollToken
      deviceFlow.value = {
        providerId: provider.id,
        providerLabel: provider.label,
        connectionProfileId: response.connectionProfileId ?? profile.id,
        deviceCode: response.deviceCode,
        userCode: response.userCode,
        verificationUri: response.verificationUri,
        verificationUriComplete: response.verificationUriComplete,
        expiresAt: response.expiresAt,
        intervalSeconds: response.intervalSeconds ?? 5,
        message: response.message,
        polling: true
      }
      pollDeviceConnection(token)
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
    form.connecting = false
  }
}

async function pollDeviceConnection(token: number) {
  while (deviceFlow.value && token === devicePollToken) {
    const current = deviceFlow.value
    await new Promise(resolve => setTimeout(resolve, Math.max(5, current.intervalSeconds) * 1000))
    if (!deviceFlow.value || token !== devicePollToken) return
    try {
      const response = await completeDeviceConnection(
        current.providerId,
        current.deviceCode,
        current.connectionProfileId
      )
      if (response?.mode === 'READY') {
        deviceFlow.value = null
        models.value = []
        await refreshStatus()
        toast.add({
          title: t('settings.deviceConnectedTitle', { provider: current.providerLabel }),
          description: t('settings.keySavedDescription'),
          color: 'primary'
        })
        return
      }
      if (response?.mode === 'PENDING') {
        deviceFlow.value = {
          ...current,
          intervalSeconds: response.intervalSeconds ?? current.intervalSeconds,
          expiresAt: response.expiresAt ?? current.expiresAt,
          message: response.message,
          polling: true
        }
        continue
      }
      deviceFlow.value = { ...current, message: response?.message, polling: false }
      toast.add({
        title: t('settings.connectionUnavailableTitle'),
        description: response?.message || t('settings.connectionUnavailableDescription'),
        color: 'warning'
      })
      return
    } catch (err) {
      deviceFlow.value = { ...current, polling: false }
      toast.add({
        title: t('settings.connectionStartFailed'),
        description: tt(err),
        color: 'error'
      })
      return
    }
  }
}

const closeDeviceFlow = () => {
  devicePollToken += 1
  deviceFlow.value = null
}

const deviceFlowOpen = computed({
  get: () => Boolean(deviceFlow.value),
  set: (open: boolean) => {
    if (!open) closeDeviceFlow()
  }
})

async function handleDeleteConnection(provider: ProviderCatalogItem, connection: ProviderConnectionSummary) {
  const form = providerForm(provider)
  form.deletingConnectionId = connection.id
  try {
    await deleteConnection(connection.id)
    if (connection.defaultModelId && form.modelId === connection.defaultModelId) {
      form.modelId = ''
    }
    models.value = []
    await refreshStatus()
    toast.add({
      title: t('settings.keyRemovedTitle', { provider: provider.label }),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.keyRemoveFailed', { provider: provider.label }),
      description: tt(err),
      color: 'error'
    })
  } finally {
    form.deletingConnectionId = null
  }
}

const openProviderDocs = (profile: ProviderConnectionProfile) => {
  if (!profile.docsUrl) return
  window.open(profile.docsUrl, '_blank', 'noopener,noreferrer')
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

        <div v-if="!settingsPageReady" class="space-y-4">
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
                    <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                      {{ t('settings.ourProvidersHeading') }}
                    </h2>
                    <p class="text-sm text-gray-500 dark:text-gray-400 max-w-2xl">
                      {{ t('settings.ourProvidersDescription') }}
                    </p>
                  </div>
                  <div class="flex items-center gap-2 flex-wrap">
                    <UBadge
                      color="neutral"
                      variant="soft"
                      :label="t('settings.providersCount', { count: providerCatalog.length })"
                    />
                    <UButton
                      to="/models"
                      color="neutral"
                      variant="outline"
                      size="sm"
                      icon="i-heroicons-cpu-chip"
                      :label="t('settings.browseModels')"
                    />
                  </div>
                </div>

                <div v-if="loadingStatus" class="space-y-3">
                  <div class="skeleton-shimmer h-20" />
                  <div class="skeleton-shimmer h-20" />
                  <div class="skeleton-shimmer h-20" />
                </div>

                <div
                  v-else
                  class="divide-y divide-gray-200 dark:divide-gray-800 rounded-md border border-gray-200 dark:border-gray-800"
                >
                  <div
                    v-for="provider in providerCatalog"
                    :key="provider.id"
                    class="grid grid-cols-1 gap-5 px-4 py-5 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,28rem)] lg:items-start"
                  >
                    <div class="min-w-0 space-y-3">
                      <div class="flex items-start justify-between gap-3 flex-wrap">
                        <div class="min-w-0 space-y-1">
                          <div class="flex items-center gap-2 flex-wrap">
                            <span
                              class="status-dot"
                              :class="providerReady(provider) ? 'status-dot-active' : 'status-dot-inactive'"
                            />
                            <span class="font-semibold text-gray-900 dark:text-gray-50">
                              {{ provider.label }}
                            </span>
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
                          <p class="text-xs text-gray-500 dark:text-gray-400">
                            {{ provider.category }} · {{ provider.execution }}
                          </p>
                        </div>
                        <UBadge
                          variant="soft"
                          :color="providerReady(provider) ? 'primary' : 'neutral'"
                          :label="providerReady(provider) ? t('settings.stored') : t('settings.notStored')"
                        />
                      </div>

                      <div class="flex items-center gap-2 flex-wrap">
                        <UBadge
                          v-for="profile in provider.connectionProfiles"
                          :key="`${provider.id}-${profile.id}`"
                          :color="profileStatusColor(provider, profile)"
                          variant="soft"
                          :label="profile.label"
                        />
                      </div>

                      <div
                        v-if="provider.connections.length"
                        class="flex items-center gap-2 flex-wrap text-xs text-gray-500 dark:text-gray-400"
                      >
                        <span>{{ t('settings.activeConnections') }}</span>
                        <UBadge
                          v-for="connection in provider.connections"
                          :key="connection.id"
                          color="primary"
                          variant="soft"
                          :label="connection.defaultModelId || connection.displayName || authMethodLabel(connection.authMethod)"
                        />
                      </div>
                    </div>

                    <div class="space-y-3">
                      <UFormField
                        :label="t('settings.connectionProfileLabel')"
                        :name="`${provider.id}-profile`"
                      >
                        <USelect
                          :model-value="providerForm(provider).selectedProfileId"
                          :items="profileItems(provider)"
                          value-key="value"
                          label-key="label"
                          icon="i-heroicons-adjustments-horizontal"
                          class="w-full"
                          :aria-label="t('settings.connectionProfileAria', { provider: provider.label })"
                          @update:model-value="value => handleProfileChange(provider, String(value))"
                        />
                      </UFormField>

                      <div class="flex items-center gap-2 flex-wrap">
                        <UBadge
                          color="neutral"
                          variant="soft"
                          :label="authMethodLabel(selectedProfile(provider)?.authMethod ?? 'API_KEY')"
                        />
                        <UBadge
                          :color="profileStatusColor(provider, selectedProfile(provider)!)"
                          variant="soft"
                          :label="connectionStatusLabel(provider, selectedProfile(provider)!)"
                        />
                      </div>

                      <UFormField
                        v-if="selectedProfile(provider)?.requiresModelId"
                        :label="t('settings.gatewayModelIdLabel')"
                        :name="`${provider.id}-model`"
                      >
                        <UInput
                          v-model="providerForm(provider).modelId"
                          :placeholder="t('settings.gatewayModelIdPlaceholder')"
                          icon="i-heroicons-cpu-chip"
                          autocomplete="off"
                          class="w-full"
                        />
                      </UFormField>

                      <UFormField
                        v-if="selectedProfile(provider)?.requiresApiKey"
                        :label="t('settings.gatewayApiKeyLabel')"
                        :description="t('settings.providerApiKeyDescription')"
                        :name="`${provider.id}-api-key`"
                      >
                        <UInput
                          v-model="providerForm(provider).apiKey"
                          :type="providerForm(provider).reveal ? 'text' : 'password'"
                          :placeholder="t('settings.gatewayApiKeyPlaceholder')"
                          icon="i-heroicons-lock-closed"
                          autocomplete="off"
                          class="w-full"
                        />
                      </UFormField>

                      <div v-if="needsEndpointOverride(selectedProfile(provider))" class="space-y-2">
                        <UButton
                          type="button"
                          variant="ghost"
                          color="neutral"
                          size="sm"
                          icon="i-heroicons-link"
                          :label="providerForm(provider).advanced ? t('settings.hideEndpointOverride') : t('settings.showEndpointOverride')"
                          @click="providerForm(provider).advanced = !providerForm(provider).advanced"
                        />
                        <UFormField
                          v-if="providerForm(provider).advanced"
                          :label="t('settings.endpointUrlLabel')"
                          :description="t('settings.endpointOverrideDescription')"
                          :name="`${provider.id}-endpoint`"
                        >
                          <UInput
                            v-model="providerForm(provider).baseUrl"
                            :placeholder="selectedProfile(provider)?.defaultBaseUrl || 'https://api.provider.com/v1'"
                            icon="i-heroicons-link"
                            autocomplete="off"
                            class="w-full"
                          />
                        </UFormField>
                      </div>

                      <p
                        v-if="!isProfileConfigurable(selectedProfile(provider)) && !selectedProfile(provider)?.oauthEnabled"
                        class="text-xs text-gray-500 dark:text-gray-400"
                      >
                        {{ t('settings.profileNotConfigurable') }}
                      </p>

                      <div class="flex items-center justify-between gap-3 flex-wrap pt-2">
                        <div class="flex items-center gap-2 flex-wrap">
                          <UButton
                            v-if="selectedProfile(provider)?.requiresApiKey"
                            type="button"
                            variant="ghost"
                            color="neutral"
                            size="sm"
                            :icon="providerForm(provider).reveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                            :label="providerForm(provider).reveal ? t('common.hide') : t('common.show')"
                            @click="providerForm(provider).reveal = !providerForm(provider).reveal"
                          />
                          <UButton
                            v-if="isProfileConfigurable(selectedProfile(provider))"
                            type="button"
                            color="primary"
                            size="sm"
                            icon="i-heroicons-check-circle"
                            :label="t('settings.saveConnection')"
                            class="btn-interactive"
                            :loading="providerForm(provider).saving"
                            :disabled="saveDisabled(provider)"
                            @click="handleSaveProvider(provider)"
                          />
                          <UButton
                            v-if="selectedProfile(provider)?.oauthEnabled"
                            type="button"
                            variant="outline"
                            color="neutral"
                            size="sm"
                            icon="i-heroicons-link"
                            :label="connectLabel(selectedProfile(provider))"
                            :loading="providerForm(provider).connecting"
                            :disabled="providerForm(provider).saving || Boolean(providerForm(provider).deletingConnectionId)"
                            @click="handleStartConnection(provider)"
                          />
                          <UButton
                            v-if="selectedProfile(provider)?.docsUrl"
                            type="button"
                            variant="ghost"
                            color="neutral"
                            size="sm"
                            icon="i-heroicons-book-open"
                            :label="t('settings.providerDocs')"
                            @click="openProviderDocs(selectedProfile(provider)!)"
                          />
                        </div>
                        <UButton
                          v-if="connectionForProfile(provider, selectedProfile(provider))"
                          type="button"
                          variant="outline"
                          color="error"
                          size="sm"
                          icon="i-heroicons-trash"
                          :label="t('common.remove')"
                          :loading="providerForm(provider).deletingConnectionId === connectionForProfile(provider, selectedProfile(provider))?.id"
                          :disabled="providerForm(provider).saving || providerForm(provider).connecting"
                          @click="handleDeleteConnection(provider, connectionForProfile(provider, selectedProfile(provider))!)"
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </UCard>

              <UModal
                v-if="deviceFlow"
                v-model:open="deviceFlowOpen"
                :title="t('settings.deviceFlowTitle', { provider: deviceFlow.providerLabel })"
                :description="t('settings.deviceFlowDescription')"
                :ui="{ width: 'max-w-md' }"
              >
                <template #body>
                  <div class="space-y-5">
                    <div class="rounded-md border border-gray-200 dark:border-gray-800 px-4 py-3">
                      <div class="text-xs uppercase text-gray-500 dark:text-gray-400">
                        {{ t('settings.deviceUserCodeLabel') }}
                      </div>
                      <div class="mt-1 font-mono text-2xl tracking-widest text-gray-900 dark:text-gray-50">
                        {{ deviceFlow.userCode }}
                      </div>
                    </div>

                    <UButton
                      :to="deviceFlow.verificationUriComplete || deviceFlow.verificationUri"
                      target="_blank"
                      color="primary"
                      icon="i-heroicons-arrow-top-right-on-square"
                      :label="t('settings.deviceVerificationLink')"
                      block
                    />

                    <p class="text-sm text-gray-500 dark:text-gray-400">
                      {{ deviceFlow.message || t('settings.devicePolling') }}
                    </p>
                    <UBadge
                      :color="deviceFlow.polling ? 'primary' : 'neutral'"
                      variant="soft"
                      :label="deviceFlow.polling ? t('settings.devicePollingBadge') : t('settings.devicePausedBadge')"
                    />
                  </div>
                </template>
                <template #footer>
                  <div class="flex justify-end">
                    <UButton
                      type="button"
                      variant="ghost"
                      color="neutral"
                      :label="t('common.cancel')"
                      @click="closeDeviceFlow"
                    />
                  </div>
                </template>
              </UModal>
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
