<script setup lang="ts">
import type {Provider} from '~/composables/useChatApi'
import type { BillingMode } from '~/composables/useEntitlements'

const { t, locale, locales, setLocale } = useI18n()
const tt = useTranslatedError()

definePageMeta({
  layout: 'default'
})

const { settings, hydrated, saveBillingMode} = useUserSettings()
const {getStatus, saveKey, deleteKey} = useKeyApi()
const {getCustomInstructions, saveCustomInstructions} = useInstructionsApi()
const {deleteAccount} = useChatApi()
const {entitlements, plan, ensureLoaded, load: loadEntitlements} = useEntitlements()
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
    billingMode.value = loaded?.billingModePreference ?? entitlements.value.billingModePreference
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
      description: t('settings.missingKeyDescription', { provider: providerDisplay(provider) }),
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
      title: t('settings.keySavedTitle', { provider: providerDisplay(provider) }),
      description: t('settings.keySavedDescription'),
      color: 'primary'
    })
  } catch (err) {
    toast.add({
      title: t('settings.keySaveFailed', { provider: providerDisplay(provider) }),
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
      title: t('settings.keyRemovedTitle', { provider: providerDisplay(provider) }),
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: t('settings.keyRemoveFailed', { provider: providerDisplay(provider) }),
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
          <UTabs
            v-model="activeTab"
            :items="tabItems"
            :content="false"
            color="primary"
            variant="link"
            class="w-full"
          />

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
