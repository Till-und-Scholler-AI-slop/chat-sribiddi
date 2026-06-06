<script setup lang="ts">
import ProviderLogo from '~/components/settings/ProviderLogo.vue'
import type {
  ProviderAuthMethod,
  ProviderCatalogItem,
  ProviderConnectionProfile,
  ProviderConnectionStatus,
  SaveProviderCredentialInput
} from '~/composables/useKeyApi'

const props = defineProps<{
  open: boolean
  providers: ProviderCatalogItem[]
  prefillProviderId?: string | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  refresh: []
  'start-chatting': []
}>()

const { t } = useI18n()
const tt = useTranslatedError()
const toast = useToast()
const { saveProviderCredential, startConnection, completeDeviceConnection } = useKeyApi()
const { models } = useChatStore()

type WizardPhase = 'pick' | 'connect' | 'done'

type ProviderFormState = {
  selectedProfileId: string
  apiKey: string
  baseUrl: string
  modelId: string
  reveal: boolean
  advanced: boolean
  saving: boolean
  connecting: boolean
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

const OAUTH_HANDOFF_KEY = 'chat-sribiddi:provider-setup-oauth-handoff'

const modalOpen = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value)
})

const phase = ref<WizardPhase>('pick')
const selectedProviderIds = ref<string[]>([])
const currentIndex = ref(0)
const connectedProviderIds = ref<string[]>([])
const providerForms = reactive<Record<string, ProviderFormState>>({})
const deviceFlow = ref<DeviceFlowState | null>(null)
let devicePollToken = 0

const providerById = computed(() => new Map(props.providers.map(provider => [provider.id, provider])))
const availableProviders = computed(() =>
  props.providers.filter(provider => usableProfiles(provider).length > 0)
)
const selectedProviders = computed(() =>
  selectedProviderIds.value
    .map(id => providerById.value.get(id))
    .filter((provider): provider is ProviderCatalogItem => Boolean(provider))
)
const currentProvider = computed(() => selectedProviders.value[currentIndex.value])
const currentProfile = computed(() => currentProvider.value ? selectedProfile(currentProvider.value) : undefined)
const currentForm = computed(() => currentProvider.value ? providerForm(currentProvider.value) : undefined)
const totalSteps = computed(() => selectedProviderIds.value.length)
const progressValue = computed(() => {
  if (!totalSteps.value) return 0
  return Math.round(((currentIndex.value + 1) / totalSteps.value) * 100)
})
const doneProviders = computed(() =>
  connectedProviderIds.value
    .map(id => providerById.value.get(id))
    .filter((provider): provider is ProviderCatalogItem => Boolean(provider))
)
const modalWidth = computed(() => phase.value === 'pick' ? 'max-w-3xl' : 'max-w-xl')
const modalTitle = computed(() => {
  if (phase.value === 'pick') return t('settings.setup.pickHeading')
  if (phase.value === 'done') return t('settings.setup.successHeading')
  return currentProvider.value
    ? t('settings.setup.connectHeading', { provider: currentProvider.value.label })
    : t('settings.setup.title')
})
const modalDescription = computed(() => {
  if (phase.value === 'pick') return t('settings.setup.pickSub')
  if (phase.value === 'done') {
    return doneProviders.value.length
      ? t('settings.setup.successSub', { count: doneProviders.value.length })
      : t('settings.setup.successEmptySub')
  }
  return t('settings.setup.connectSub')
})
const canUsePrimaryAction = computed(() => {
  const provider = currentProvider.value
  if (!provider) return false
  return !connectDisabled(provider)
})
const primaryActionLabel = computed(() => {
  const provider = currentProvider.value
  const profile = currentProfile.value
  if (!provider || !profile) return t('settings.connectProvider')
  if (isCliDeviceCodeProfile(profile)) {
    return t('settings.connectCodexSubscription')
  }
  if (isRedirectingProfile(profile)) {
    return t('settings.setup.authorize', { provider: provider.label })
  }
  return currentIndex.value + 1 >= totalSteps.value
    ? t('settings.setup.connectFinish')
    : t('settings.setup.connectNext')
})

watch(
  () => props.open,
  (open) => {
    if (open) resetWizard(props.prefillProviderId ?? null)
    else closeDeviceFlow()
  }
)

watch(
  () => props.prefillProviderId,
  (providerId) => {
    if (props.open) resetWizard(providerId ?? null)
  }
)

watch(
  () => props.providers,
  providers => syncProviderForms(providers),
  { immediate: true }
)

onUnmounted(() => {
  devicePollToken += 1
})

function resetWizard(prefillProviderId: string | null) {
  closeDeviceFlow()
  connectedProviderIds.value = []
  currentIndex.value = 0
  if (prefillProviderId && providerById.value.has(prefillProviderId)) {
    selectedProviderIds.value = [prefillProviderId]
    phase.value = 'connect'
  } else {
    selectedProviderIds.value = []
    phase.value = 'pick'
  }
}

function authMethodLabel(method: ProviderAuthMethod) {
  const key = `settings.authMethods.${method}`
  const translated = t(key)
  return translated === key ? method.replaceAll('_', ' ') : translated
}

function statusLabel(value: ProviderConnectionStatus) {
  const key = `settings.connectionStatuses.${value}`
  const translated = t(key)
  return translated === key ? value.replaceAll('_', ' ') : translated
}

function statusColor(value: ProviderConnectionStatus) {
  if (value === 'READY') return 'primary'
  if (value === 'EXPERIMENTAL') return 'warning'
  if (value === 'DISABLED' || value === 'ERROR') return 'error'
  return 'neutral'
}

function usableProfiles(provider: ProviderCatalogItem) {
  return provider.connectionProfiles.filter(profile => profile.status !== 'DISABLED')
}

function primaryProfile(provider: ProviderCatalogItem) {
  return usableProfiles(provider)[0] ?? provider.connectionProfiles[0]
}

function profileItems(provider: ProviderCatalogItem) {
  return provider.connectionProfiles.map(profile => ({
    label: profile.label,
    value: profile.id,
    disabled: profile.status === 'DISABLED'
  }))
}

function connectionForProfile(
  provider: ProviderCatalogItem,
  profile: ProviderConnectionProfile | undefined
) {
  if (!profile) return undefined
  return provider.connections.find(connection => connection.connectionProfileId === profile.id)
}

function providerReady(provider: ProviderCatalogItem) {
  return provider.connections.some(connection => connection.status === 'READY')
}

function providerForm(provider: ProviderCatalogItem) {
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
      connecting: false
    }
  }
  return providerForms[provider.id]
}

function selectedProfile(provider: ProviderCatalogItem) {
  const form = providerForm(provider)
  return (
    provider.connectionProfiles.find(profile => profile.id === form.selectedProfileId) ??
    primaryProfile(provider)
  )
}

function syncProviderForms(providers: ProviderCatalogItem[]) {
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

function handleProfileChange(provider: ProviderCatalogItem, nextProfileId: string) {
  const form = providerForm(provider)
  const profile = provider.connectionProfiles.find(item => item.id === nextProfileId)
  if (!profile) return
  form.selectedProfileId = profile.id
  form.baseUrl = profile.defaultBaseUrl ?? ''
  form.modelId = connectionForProfile(provider, profile)?.defaultModelId ?? ''
  form.apiKey = ''
  form.advanced = false
}

function isProfileConfigurable(profile: ProviderConnectionProfile | undefined) {
  return Boolean(
    profile &&
      profile.status !== 'DISABLED' &&
      (profile.requiresApiKey ||
        profile.requiresModelId ||
        profile.baseUrlEditable ||
        profile.defaultBaseUrl)
  )
}

function needsEndpointOverride(profile: ProviderConnectionProfile | undefined) {
  return Boolean(profile?.baseUrlEditable || profile?.defaultBaseUrl)
}

function profileConnectMode(profile: ProviderConnectionProfile | undefined) {
  if (!profile) return 'FORM'
  if (profile.connectMode) return profile.connectMode
  if (profile.authMethod === 'DEVICE_CODE') return 'DEVICE_CODE'
  if (profile.oauthEnabled) return 'REDIRECT'
  return 'FORM'
}

function isRedirectingProfile(profile: ProviderConnectionProfile | undefined) {
  return profileConnectMode(profile) === 'REDIRECT'
}

function isDeviceCodeProfile(profile: ProviderConnectionProfile | undefined) {
  const mode = profileConnectMode(profile)
  return mode === 'DEVICE_CODE' || mode === 'CLI_DEVICE_CODE'
}

function isCliDeviceCodeProfile(profile: ProviderConnectionProfile | undefined) {
  return profileConnectMode(profile) === 'CLI_DEVICE_CODE'
}

function isConnectableProfile(profile: ProviderConnectionProfile | undefined) {
  return Boolean(profile && (isProfileConfigurable(profile) || profileConnectMode(profile) !== 'FORM'))
}

function connectDisabled(provider: ProviderCatalogItem) {
  const form = providerForm(provider)
  const profile = selectedProfile(provider)
  if (!isConnectableProfile(profile) || form.saving || form.connecting) return true
  if (profile && profileConnectMode(profile) !== 'FORM' && !isProfileConfigurable(profile)) return false
  if (profile?.requiresApiKey && !form.apiKey.trim()) return true
  if (profile?.requiresModelId && !form.modelId.trim()) return true
  if (needsEndpointOverride(profile) && !form.baseUrl.trim()) return true
  return false
}

function profileStatusColor(provider: ProviderCatalogItem, profile: ProviderConnectionProfile) {
  const connection = connectionForProfile(provider, profile)
  return statusColor(connection?.status ?? profile.status)
}

function toggleProvider(providerId: string) {
  if (selectedProviderIds.value.includes(providerId)) {
    selectedProviderIds.value = selectedProviderIds.value.filter(id => id !== providerId)
  } else {
    selectedProviderIds.value = [...selectedProviderIds.value, providerId]
  }
}

function beginConnect() {
  selectedProviderIds.value = sortForSetup(selectedProviderIds.value)
  currentIndex.value = 0
  phase.value = 'connect'
}

function sortForSetup(providerIds: string[]) {
  const rank = (providerId: string) => {
    const provider = providerById.value.get(providerId)
    const profile = provider ? selectedProfile(provider) : undefined
    return isRedirectingProfile(profile) ? 1 : 0
  }
  return [...providerIds].sort((left, right) => rank(left) - rank(right))
}

function stepBack() {
  if (currentIndex.value === 0) {
    if (props.prefillProviderId) {
      closeWizard()
    } else {
      phase.value = 'pick'
    }
    return
  }
  currentIndex.value -= 1
}

function skipCurrent() {
  advance()
}

function advance() {
  if (currentIndex.value + 1 >= totalSteps.value) {
    phase.value = 'done'
  } else {
    currentIndex.value += 1
  }
}

function markConnected(providerId: string) {
  if (connectedProviderIds.value.includes(providerId)) return
  connectedProviderIds.value = [...connectedProviderIds.value, providerId]
}

async function connectCurrentProvider() {
  const provider = currentProvider.value
  if (!provider) return
  const profile = selectedProfile(provider)
  if (!profile) return
  if (isProfileConfigurable(profile)) {
    const saved = await handleSaveProvider(provider, profile)
    if (saved) {
      markConnected(provider.id)
      advance()
    }
    return
  }
  if (profileConnectMode(profile) !== 'FORM') {
    await handleStartConnection(provider, profile)
  }
}

async function handleSaveProvider(provider: ProviderCatalogItem, profile: ProviderConnectionProfile) {
  const form = providerForm(provider)
  const apiKey = form.apiKey.trim()
  const baseUrl = form.baseUrl.trim()
  const modelId = form.modelId.trim()
  if (profile.requiresApiKey && !apiKey) {
    toast.add({
      title: t('settings.missingKeyTitle'),
      description: t('settings.missingKeyDescription', { provider: provider.label }),
      color: 'warning'
    })
    return false
  }
  if (needsEndpointOverride(profile) && !baseUrl) {
    toast.add({
      title: t('settings.missingEndpointTitle'),
      description: t('settings.missingEndpointDescription'),
      color: 'warning'
    })
    return false
  }
  if (profile.requiresModelId && !modelId) {
    toast.add({
      title: t('settings.missingModelIdTitle'),
      description: t('settings.missingModelIdDescription'),
      color: 'warning'
    })
    return false
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
    emit('refresh')
    toast.add({
      title: t('settings.connectionSavedTitle', { provider: provider.label }),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
    return true
  } catch (err) {
    toast.add({
      title: t('settings.connectionSaveFailed', { provider: provider.label }),
      description: tt(err),
      color: 'error'
    })
    return false
  } finally {
    form.saving = false
  }
}

async function handleStartConnection(provider: ProviderCatalogItem, profile: ProviderConnectionProfile) {
  const form = providerForm(provider)
  form.connecting = true
  try {
    const response = await startConnection(provider.id, profile.authMethod, profile.id)
    if (response?.mode === 'REDIRECT' && response.authorizationUrl) {
      rememberOAuthHandoff(provider)
      window.location.assign(response.authorizationUrl)
      return
    }
    if (
      (response?.mode === 'DEVICE_CODE' || response?.mode === 'CLI_DEVICE_CODE') &&
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
        message: response.message || (isCliDeviceCodeProfile(profile) ? t('settings.codexDevicePolling') : null),
        polling: true
      }
      pollDeviceConnection(token)
      return
    }
    if (response?.mode === 'READY') {
      models.value = []
      emit('refresh')
      markConnected(provider.id)
      advance()
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
        emit('refresh')
        markConnected(current.providerId)
        toast.add({
          title: t('settings.deviceConnectedTitle', { provider: current.providerLabel }),
          description: t('settings.keySavedDescription'),
          color: 'primary'
        })
        advance()
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

function closeDeviceFlow() {
  devicePollToken += 1
  deviceFlow.value = null
}

const deviceFlowOpen = computed({
  get: () => Boolean(deviceFlow.value),
  set: (open: boolean) => {
    if (!open) closeDeviceFlow()
  }
})

function rememberOAuthHandoff(provider: ProviderCatalogItem) {
  if (!import.meta.client) return
  sessionStorage.setItem(
    OAUTH_HANDOFF_KEY,
    JSON.stringify({
      providerId: provider.id,
      selectedProviderIds: selectedProviderIds.value,
      index: currentIndex.value,
      createdAt: Date.now()
    })
  )
}

function openProviderDocs(profile: ProviderConnectionProfile | undefined) {
  if (!profile?.docsUrl) return
  window.open(profile.docsUrl, '_blank', 'noopener,noreferrer')
}

function closeWizard() {
  modalOpen.value = false
}

function startChatting() {
  modalOpen.value = false
  emit('start-chatting')
}
</script>

<template>
  <UModal
    v-model:open="modalOpen"
    :title="modalTitle"
    :description="modalDescription"
    :ui="{ width: modalWidth }"
  >
    <template #body>
      <div v-if="phase === 'pick'" class="space-y-5">
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <button
            v-for="provider in availableProviders"
            :key="provider.id"
            type="button"
            class="group flex min-h-28 w-full items-start gap-3 rounded-xl border p-4 text-left transition hover:-translate-y-0.5 hover:border-[var(--accent-line)] hover:bg-[var(--accent-soft)]/45"
            :class="selectedProviderIds.includes(provider.id)
              ? 'border-[var(--accent-line)] bg-[var(--accent-soft)] shadow-sm'
              : 'border-gray-200 bg-white/45 dark:border-gray-800 dark:bg-gray-900/35'"
            @click="toggleProvider(provider.id)"
          >
            <ProviderLogo :provider="provider" />
            <span class="min-w-0 flex-1 space-y-2">
              <span class="flex items-start justify-between gap-3">
                <span class="min-w-0">
                  <span class="block text-sm font-semibold text-gray-900 dark:text-gray-50">
                    {{ provider.label }}
                  </span>
                  <span class="mt-1 block text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                    {{ t('settings.setup.providerMeta', {
                      category: provider.category,
                      methods: provider.authMethods.map(authMethodLabel).join(', ')
                    }) }}
                  </span>
                </span>
                <span
                  class="inline-flex size-6 shrink-0 items-center justify-center rounded-lg border transition"
                  :class="selectedProviderIds.includes(provider.id)
                    ? 'border-[var(--accent)] bg-[var(--accent)] text-white'
                    : 'border-gray-300 bg-white text-transparent dark:border-gray-700 dark:bg-gray-900'"
                >
                  <UIcon name="i-heroicons-check" class="size-4" />
                </span>
              </span>
              <span class="flex flex-wrap gap-1.5">
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
                  v-if="providerReady(provider)"
                  color="primary"
                  variant="soft"
                  :label="t('settings.readyConnection')"
                />
              </span>
            </span>
          </button>
        </div>

        <div
          v-if="!availableProviders.length"
          class="rounded-xl border border-dashed border-gray-300 px-5 py-8 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400"
        >
          {{ t('settings.setup.noProviders') }}
        </div>
      </div>

      <div v-else-if="phase === 'done'" class="space-y-6 text-center">
        <div class="mx-auto flex size-16 items-center justify-center rounded-2xl bg-[var(--accent)] text-white shadow-lg shadow-primary/20">
          <UIcon name="i-heroicons-check" class="size-9" />
        </div>
        <div v-if="doneProviders.length" class="mx-auto max-w-sm space-y-2 text-left">
          <div
            v-for="provider in doneProviders"
            :key="provider.id"
            class="flex items-center gap-3 rounded-xl border border-gray-200 bg-white/45 px-3 py-2.5 dark:border-gray-800 dark:bg-gray-900/35"
          >
            <ProviderLogo :provider="provider" size="sm" />
            <span class="min-w-0 flex-1 truncate text-sm font-semibold text-gray-900 dark:text-gray-50">
              {{ provider.label }}
            </span>
            <UBadge
              color="primary"
              variant="soft"
              icon="i-heroicons-check-circle"
              :label="t('settings.readyConnection')"
            />
          </div>
        </div>
      </div>

      <div v-else-if="currentProvider && currentProfile && currentForm" class="space-y-5">
        <div class="space-y-3">
          <div class="flex items-center justify-between gap-3 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
            <span>
              {{ t('settings.setup.stepOf', { current: currentIndex + 1, total: totalSteps }) }}
            </span>
            <span>{{ progressValue }}%</span>
          </div>
          <div class="h-1.5 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-800">
            <div class="h-full rounded-full bg-[var(--accent)] transition-all" :style="{ width: `${progressValue}%` }" />
          </div>
          <div class="flex items-center gap-1.5 overflow-x-auto pb-1">
            <template v-for="(providerId, index) in selectedProviderIds" :key="providerId">
              <div v-if="index > 0" class="h-px min-w-5 flex-1 bg-gray-200 dark:bg-gray-800" />
              <div
                class="relative rounded-xl p-1 transition"
                :class="[
                  index === currentIndex ? 'ring-2 ring-[var(--accent)]' : '',
                  connectedProviderIds.includes(providerId) ? 'opacity-100' : index <= currentIndex ? 'opacity-90' : 'opacity-45'
                ]"
              >
                <ProviderLogo
                  v-if="providerById.get(providerId)"
                  :provider="providerById.get(providerId)!"
                  size="sm"
                />
                <span
                  v-if="connectedProviderIds.includes(providerId)"
                  class="absolute -bottom-0.5 -right-0.5 inline-flex size-4 items-center justify-center rounded-full bg-[var(--accent)] text-white ring-2 ring-[var(--paper)]"
                >
                  <UIcon name="i-heroicons-check" class="size-2.5" />
                </span>
              </div>
            </template>
          </div>
        </div>

        <div class="flex items-center gap-3 rounded-xl border border-gray-200 bg-white/45 p-4 dark:border-gray-800 dark:bg-gray-900/35">
          <ProviderLogo :provider="currentProvider" size="lg" />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <h3 class="truncate text-base font-semibold text-gray-900 dark:text-gray-50">
                {{ currentProvider.label }}
              </h3>
              <UBadge
                :color="statusColor(currentProvider.status)"
                variant="soft"
                :label="statusLabel(currentProvider.status)"
              />
            </div>
            <div class="mt-2 flex flex-wrap gap-1.5">
              <UBadge
                color="neutral"
                variant="soft"
                :label="authMethodLabel(currentProfile.authMethod)"
              />
              <UBadge
                :color="profileStatusColor(currentProvider, currentProfile)"
                variant="soft"
                :label="statusLabel(connectionForProfile(currentProvider, currentProfile)?.status ?? currentProfile.status)"
              />
              <UBadge
                v-if="currentProvider.experimental"
                color="warning"
                variant="soft"
                :label="t('settings.experimental')"
              />
            </div>
          </div>
        </div>

        <UFormField
          v-if="currentProvider.connectionProfiles.length > 1"
          :label="t('settings.connectionProfileLabel')"
          :name="`${currentProvider.id}-setup-profile`"
        >
          <USelect
            :model-value="currentForm.selectedProfileId"
            :items="profileItems(currentProvider)"
            value-key="value"
            label-key="label"
            icon="i-heroicons-adjustments-horizontal"
            class="w-full"
            :aria-label="t('settings.connectionProfileAria', { provider: currentProvider.label })"
            @update:model-value="value => handleProfileChange(currentProvider, String(value))"
          />
        </UFormField>

        <div v-if="isRedirectingProfile(currentProfile)" class="flex gap-3 rounded-xl border border-[var(--accent-line)] bg-[var(--accent-soft)]/60 p-4">
          <UIcon name="i-heroicons-shield-check" class="mt-0.5 size-5 shrink-0 text-[var(--accent-ink)]" />
          <p class="text-sm leading-relaxed text-gray-700 dark:text-gray-200">
            {{ t('settings.setup.oauthHandoff', { provider: currentProvider.label }) }}
          </p>
        </div>

        <div v-else-if="isDeviceCodeProfile(currentProfile)" class="flex gap-3 rounded-xl border border-gray-200 bg-white/45 p-4 dark:border-gray-800 dark:bg-gray-900/35">
          <UIcon name="i-heroicons-device-phone-mobile" class="mt-0.5 size-5 shrink-0 text-gray-500" />
          <p class="text-sm leading-relaxed text-gray-600 dark:text-gray-300">
            {{ isCliDeviceCodeProfile(currentProfile) ? t('settings.setup.codexDeviceCodeHint') : t('settings.setup.deviceCodeHint') }}
          </p>
        </div>

        <UFormField
          v-if="currentProfile.requiresModelId"
          :label="t('settings.gatewayModelIdLabel')"
          :name="`${currentProvider.id}-setup-model`"
        >
          <UInput
            v-model="currentForm.modelId"
            :placeholder="t('settings.gatewayModelIdPlaceholder')"
            icon="i-heroicons-cpu-chip"
            autocomplete="off"
            class="w-full"
          />
        </UFormField>

        <UFormField
          v-if="currentProfile.requiresApiKey"
          :label="t('settings.gatewayApiKeyLabel')"
          :description="t('settings.providerApiKeyDescription')"
          :name="`${currentProvider.id}-setup-api-key`"
        >
          <div class="flex gap-2">
            <UInput
              v-model="currentForm.apiKey"
              :type="currentForm.reveal ? 'text' : 'password'"
              :placeholder="t('settings.gatewayApiKeyPlaceholder')"
              icon="i-heroicons-lock-closed"
              autocomplete="off"
              class="min-w-0 flex-1"
            />
            <UTooltip :text="currentForm.reveal ? t('common.hide') : t('common.show')">
              <UButton
                type="button"
                variant="outline"
                color="neutral"
                :icon="currentForm.reveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
                :aria-label="currentForm.reveal ? t('common.hide') : t('common.show')"
                @click="currentForm.reveal = !currentForm.reveal"
              />
            </UTooltip>
          </div>
        </UFormField>

        <div v-if="needsEndpointOverride(currentProfile)" class="space-y-2">
          <UButton
            type="button"
            variant="ghost"
            color="neutral"
            size="sm"
            icon="i-heroicons-link"
            :label="currentForm.advanced ? t('settings.hideEndpointOverride') : t('settings.showEndpointOverride')"
            @click="currentForm.advanced = !currentForm.advanced"
          />
          <UFormField
            v-if="currentForm.advanced"
            :label="t('settings.endpointUrlLabel')"
            :description="t('settings.endpointOverrideDescription')"
            :name="`${currentProvider.id}-setup-endpoint`"
          >
            <UInput
              v-model="currentForm.baseUrl"
              :placeholder="currentProfile.defaultBaseUrl || t('settings.endpointUrlPlaceholder')"
              icon="i-heroicons-link"
              autocomplete="off"
              class="w-full"
            />
          </UFormField>
        </div>

        <p
          v-if="!isConnectableProfile(currentProfile)"
          class="text-sm text-gray-500 dark:text-gray-400"
        >
          {{ t('settings.profileNotConfigurable') }}
        </p>

        <UButton
          v-if="currentProfile.docsUrl"
          type="button"
          variant="ghost"
          color="neutral"
          size="sm"
          icon="i-heroicons-book-open"
          :label="t('settings.providerDocs')"
          @click="openProviderDocs(currentProfile)"
        />
      </div>
    </template>

    <template #footer>
      <div v-if="phase === 'pick'" class="flex w-full items-center gap-3">
        <span class="text-sm text-gray-500 dark:text-gray-400">
          {{ t('settings.setup.selectedCount', { count: selectedProviderIds.length }) }}
        </span>
        <div class="ml-auto flex gap-2">
          <UButton
            type="button"
            variant="ghost"
            color="neutral"
            :label="t('common.cancel')"
            @click="closeWizard"
          />
          <UButton
            type="button"
            color="primary"
            icon="i-heroicons-arrow-right"
            trailing
            :label="t('settings.setup.continue')"
            :disabled="selectedProviderIds.length === 0"
            @click="beginConnect"
          />
        </div>
      </div>

      <div v-else-if="phase === 'done'" class="ml-auto flex gap-2">
        <UButton
          type="button"
          variant="outline"
          color="neutral"
          :label="t('settings.setup.backToSettings')"
          @click="closeWizard"
        />
        <UButton
          type="button"
          color="primary"
          icon="i-heroicons-sparkles"
          :label="t('settings.setup.startChatting')"
          @click="startChatting"
        />
      </div>

      <div v-else class="flex w-full items-center gap-3">
        <UButton
          type="button"
          variant="ghost"
          color="neutral"
          icon="i-heroicons-arrow-left"
          :label="t('settings.setup.back')"
          @click="stepBack"
        />
        <div class="ml-auto flex gap-2">
          <UButton
            type="button"
            variant="ghost"
            color="neutral"
            :label="t('settings.setup.skip')"
            @click="skipCurrent"
          />
          <UButton
            type="button"
            color="primary"
            :icon="isDeviceCodeProfile(currentProfile) ? 'i-heroicons-device-phone-mobile' : currentProfile?.oauthEnabled ? 'i-heroicons-link' : 'i-heroicons-check-circle'"
            :label="primaryActionLabel"
            :loading="currentForm?.saving || currentForm?.connecting"
            :disabled="!canUsePrimaryAction"
            @click="connectCurrentProvider"
          />
        </div>
      </div>
    </template>
  </UModal>

  <UModal
    v-if="deviceFlow"
    v-model:open="deviceFlowOpen"
    :title="t('settings.deviceFlowTitle', { provider: deviceFlow.providerLabel })"
    :description="t('settings.deviceFlowDescription')"
    :ui="{ width: 'max-w-md' }"
  >
    <template #body>
      <div class="space-y-5">
        <div class="rounded-md border border-gray-200 px-4 py-3 dark:border-gray-800">
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
