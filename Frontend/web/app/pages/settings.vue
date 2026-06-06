<script setup lang="ts">
import ProviderLogo from '~/components/settings/ProviderLogo.vue'
import ProviderSetupWizard from '~/components/settings/ProviderSetupWizard.vue'
import type {
  ProviderAuthMethod,
  ProviderCatalogItem,
  ProviderConnectionProfile,
  ProviderConnectionSummary,
  ProviderConnectionStatus
} from '~/composables/useKeyApi'

const { t, locale, locales, setLocale } = useI18n()
const tt = useTranslatedError()

definePageMeta({
  layout: 'default'
})

const { settings } = useUserSettings()
const {
  getProviders,
  deleteConnection
} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {deleteAccount} = useChatApi()
const {models, ensureModels} = useChatStore()
const {status: authStatus} = useAuth()
const toast = useToast()
const router = useRouter()
const route = useRoute()

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
const providerSetupOpen = ref(false)
const providerSetupPrefill = ref<string | null>(null)
const providerSetupAutoChecked = ref(false)
const handledProviderCallback = ref(false)
const deletingConnectionId = ref<number | null>(null)

const PROVIDER_SETUP_SEEN_KEY = 'chat-sribiddi:provider-setup-seen'
const OAUTH_HANDOFF_KEY = 'chat-sribiddi:provider-setup-oauth-handoff'

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

const usableProfiles = (provider: ProviderCatalogItem) =>
  provider.connectionProfiles.filter(profile => profile.status !== 'DISABLED')

const providerReady = (provider: ProviderCatalogItem) =>
  provider.connections.some(connection => connection.status === 'READY')

const connectedProviders = computed(() =>
  providerCatalog.value.filter(provider => providerReady(provider))
)

const availableProviders = computed(() =>
  providerCatalog.value.filter(provider => !providerReady(provider) && usableProfiles(provider).length > 0)
)

const readyConnections = (provider: ProviderCatalogItem) =>
  provider.connections.filter(connection => connection.status === 'READY')

const connectionLabel = (connection: ProviderConnectionSummary) =>
  connection.defaultModelId ||
  connection.displayName ||
  connection.providerAccountLabel ||
  authMethodLabel(connection.authMethod)

const firstDocsProfile = (provider: ProviderCatalogItem) =>
  provider.connectionProfiles.find(profile => profile.docsUrl)

const refreshStatus = async () => {
  loadingStatus.value = true
  try {
    const providers = await getProviders()
    providerCatalog.value = providers
    maybeAutoOpenProviderSetup()
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
  handleProviderCallbackQuery()
})

async function handleDeleteConnection(provider: ProviderCatalogItem, connection: ProviderConnectionSummary) {
  deletingConnectionId.value = connection.id
  try {
    await deleteConnection(connection.id)
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
    deletingConnectionId.value = null
  }
}

const openProviderDocs = (profile: ProviderConnectionProfile) => {
  if (!profile.docsUrl) return
  window.open(profile.docsUrl, '_blank', 'noopener,noreferrer')
}

const openProviderSetup = (providerId: string | null = null) => {
  providerSetupPrefill.value = providerId
  providerSetupOpen.value = true
  if (import.meta.client) {
    localStorage.setItem(PROVIDER_SETUP_SEEN_KEY, '1')
  }
}

const handleProviderSetupOpenChange = (open: boolean) => {
  providerSetupOpen.value = open
  if (!open) {
    providerSetupPrefill.value = null
  }
}

const maybeAutoOpenProviderSetup = () => {
  if (providerSetupAutoChecked.value || !import.meta.client) return
  if (!providerCatalog.value.length || connectedProviders.value.length > 0) return
  providerSetupAutoChecked.value = true
  if (localStorage.getItem(PROVIDER_SETUP_SEEN_KEY) === '1') return
  openProviderSetup()
}

const handleWizardRefresh = async () => {
  try {
    await refreshStatus()
  } catch (err) {
    toast.add({
      title: t('settings.loadKeyStatusFailed'),
      description: tt(err),
      color: 'error'
    })
  }
}

const handleStartChatting = async () => {
  await router.push('/chat')
}

const firstQueryValue = (value: unknown) => Array.isArray(value) ? value[0] : value

const handleProviderCallbackQuery = () => {
  if (handledProviderCallback.value) return
  const connected = firstQueryValue(route.query.connected)
  if (connected !== '1' && connected !== '0') return
  handledProviderCallback.value = true
  const providerId = String(firstQueryValue(route.query.provider) ?? '')
  const provider = providerCatalog.value.find(item => item.id === providerId)
  const providerLabel = provider?.label || providerId || t('settings.providerFallbackLabel')
  if (import.meta.client) {
    sessionStorage.removeItem(OAUTH_HANDOFF_KEY)
  }
  if (connected === '1') {
    toast.add({
      title: t('settings.oauthConnectedTitle', { provider: providerLabel }),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } else {
    const error = firstQueryValue(route.query.error)
    toast.add({
      title: t('settings.oauthConnectionFailedTitle', { provider: providerLabel }),
      description: typeof error === 'string' && error ? error : t('settings.oauthConnectionFailedDescription'),
      color: 'error'
    })
  }
  const nextQuery = { ...route.query }
  delete nextQuery.connected
  delete nextQuery.provider
  delete nextQuery.error
  router.replace({ path: route.path, query: nextQuery }).catch(() => undefined)
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
                      {{ t('settings.providersHeading') }}
                    </h2>
                    <p class="text-sm text-gray-500 dark:text-gray-400 max-w-2xl">
                      {{ t('settings.providersDescription') }}
                    </p>
                  </div>
                  <div class="flex items-center gap-2 flex-wrap">
                    <UBadge
                      color="neutral"
                      variant="soft"
                      :label="t('settings.connectedProvidersCount', { count: connectedProviders.length })"
                    />
                    <UButton
                      type="button"
                      color="primary"
                      size="sm"
                      icon="i-heroicons-sparkles"
                      :label="t('settings.setUpProviders')"
                      @click="openProviderSetup()"
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
                  v-else-if="connectedProviders.length === 0"
                  class="rounded-xl border border-dashed border-gray-300 bg-[var(--accent-soft)]/20 px-6 py-10 text-center dark:border-gray-700"
                >
                  <div class="mx-auto mb-4 flex size-14 items-center justify-center rounded-2xl bg-[var(--accent-soft)] text-[var(--accent-ink)]">
                    <UIcon name="i-heroicons-sparkles" class="size-7" />
                  </div>
                  <h3 class="font-serif-display text-xl text-gray-900 dark:text-gray-50">
                    {{ t('settings.noProvidersConnectedTitle') }}
                  </h3>
                  <p class="mx-auto mt-2 max-w-md text-sm text-gray-500 dark:text-gray-400">
                    {{ t('settings.noProvidersConnectedDescription') }}
                  </p>
                  <UButton
                    type="button"
                    color="primary"
                    icon="i-heroicons-sparkles"
                    :label="t('settings.chooseProviders')"
                    class="mt-6"
                    @click="openProviderSetup()"
                  />
                </div>

                <div
                  v-else
                  class="divide-y divide-gray-200 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800"
                >
                  <div
                    v-for="provider in connectedProviders"
                    :key="provider.id"
                    class="flex flex-col gap-4 px-4 py-5 sm:flex-row sm:items-start"
                  >
                    <ProviderLogo :provider="provider" size="lg" />
                    <div class="min-w-0 flex-1 space-y-3">
                      <div class="flex items-start justify-between gap-3 flex-wrap">
                        <div class="min-w-0 space-y-1">
                          <div class="flex items-center gap-2 flex-wrap">
                            <span class="status-dot status-dot-active" />
                            <span class="font-semibold text-gray-900 dark:text-gray-50">
                              {{ provider.label }}
                            </span>
                            <UBadge
                              color="primary"
                              variant="soft"
                              icon="i-heroicons-check-circle"
                              :label="t('settings.readyConnection')"
                            />
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
                          </div>
                          <p class="text-xs text-gray-500 dark:text-gray-400">
                            {{ provider.category }} · {{ provider.execution }}
                          </p>
                        </div>
                        <UBadge
                          :color="statusColor(provider.status)"
                          variant="soft"
                          :label="statusLabel(provider.status)"
                        />
                      </div>

                      <div
                        v-if="readyConnections(provider).length"
                        class="flex flex-wrap gap-2"
                      >
                        <span
                          v-for="connection in readyConnections(provider)"
                          :key="connection.id"
                          class="inline-flex max-w-full items-center gap-2 rounded-full border border-gray-200 bg-white/50 px-2.5 py-1.5 text-xs text-gray-600 dark:border-gray-800 dark:bg-gray-900/40 dark:text-gray-300"
                        >
                          <UIcon name="i-heroicons-key" class="size-3.5 shrink-0" />
                          <span class="min-w-0 truncate">{{ connectionLabel(connection) }}</span>
                          <UTooltip :text="t('settings.removeConnectionAria', { provider: provider.label })">
                            <UButton
                              type="button"
                              variant="ghost"
                              color="error"
                              size="xs"
                              icon="i-heroicons-x-mark"
                              :aria-label="t('settings.removeConnectionAria', { provider: provider.label })"
                              :loading="deletingConnectionId === connection.id"
                              :disabled="deletingConnectionId !== null && deletingConnectionId !== connection.id"
                              @click="handleDeleteConnection(provider, connection)"
                            />
                          </UTooltip>
                        </span>
                      </div>
                    </div>

                    <div class="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
                      <UButton
                        type="button"
                        variant="outline"
                        color="neutral"
                        size="sm"
                        icon="i-heroicons-adjustments-horizontal"
                        :label="t('settings.manageConnection')"
                        @click="openProviderSetup(provider.id)"
                      />
                      <UButton
                        v-if="firstDocsProfile(provider)"
                        type="button"
                        variant="ghost"
                        color="neutral"
                        size="sm"
                        icon="i-heroicons-book-open"
                        :label="t('settings.providerDocs')"
                        @click="openProviderDocs(firstDocsProfile(provider)!)"
                      />
                    </div>
                  </div>
                </div>
              </UCard>

              <section
                v-if="!loadingStatus && connectedProviders.length > 0 && availableProviders.length > 0"
                class="space-y-3"
              >
                <h3 class="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {{ t('settings.addAnotherProvider') }}
                </h3>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="provider in availableProviders"
                    :key="provider.id"
                    type="button"
                    class="inline-flex min-h-11 items-center gap-2 rounded-full border border-gray-200 bg-white/40 px-2.5 pr-3 text-sm font-medium text-gray-800 transition hover:border-[var(--accent-line)] hover:bg-[var(--accent-soft)] dark:border-gray-800 dark:bg-gray-900/30 dark:text-gray-100"
                    :aria-label="t('settings.openProviderSetupFor', { provider: provider.label })"
                    @click="openProviderSetup(provider.id)"
                  >
                    <ProviderLogo :provider="provider" size="sm" />
                    <span>{{ provider.label }}</span>
                    <UIcon name="i-heroicons-plus" class="size-4 text-gray-500" />
                  </button>
                  <UButton
                      type="button"
                      variant="outline"
                      color="neutral"
                      icon="i-heroicons-sparkles"
                      :label="t('settings.setUpProviders')"
                      @click="openProviderSetup()"
                    />
                </div>
              </section>
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
  <ProviderSetupWizard
    :open="providerSetupOpen"
    :providers="providerCatalog"
    :prefill-provider-id="providerSetupPrefill"
    @update:open="handleProviderSetupOpenChange"
    @refresh="handleWizardRefresh"
    @start-chatting="handleStartChatting"
  />
  </div>
</template>
