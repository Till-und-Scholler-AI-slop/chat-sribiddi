<script setup lang="ts">
import type { ModelOption, Provider } from '~/composables/useChatApi'
import { isModelLocked } from '~/composables/useChatApi'
import { formatRatePerMillion } from '~/composables/chat-helpers'
import { PROVIDER_LABELS } from '~/utils/format'

const { t } = useI18n()
const tt = useTranslatedError()
const { status: authStatus, checkAuth } = useAuth()
const { settings } = useUserSettings()

definePageMeta({
  layout: 'default'
})

const {
  activeConversation,
  ensureModels,
  isFavorite,
  models,
  modelsError,
  modelsLoading,
  setModelForChat,
  sortedModels,
  startChat,
  toggleFavorite
} = useChatStore()

const toast = useToast()
const router = useRouter()

const providerFilter = ref<'ALL' | Provider>('ALL')
const searchQuery = ref('')
const expertModeLoading = ref(false)
const isAuthenticated = computed(() => authStatus.value === 'authenticated')

const providerInitial: Record<Provider, string> = {
  OPENAI: 'O',
  GOOGLE: 'G',
  CLAUDE: 'C',
  OPENROUTER: 'O'
}

// Provider slug used to compose the visible identifier (e.g. "openai/gpt-5")
const providerSlug: Record<Provider, string> = {
  OPENAI: 'openai',
  GOOGLE: 'google',
  CLAUDE: 'claude',
  OPENROUTER: 'openrouter'
}

const providerFilters = computed(() => [
  { label: t('models.providerFilterAllShort'), value: 'ALL' as const },
  { label: PROVIDER_LABELS.CLAUDE, value: 'CLAUDE' as const },
  { label: PROVIDER_LABELS.OPENAI, value: 'OPENAI' as const },
  { label: PROVIDER_LABELS.GOOGLE, value: 'GOOGLE' as const },
  { label: PROVIDER_LABELS.OPENROUTER, value: 'OPENROUTER' as const }
])

const normalizedSearchQuery = computed(() => searchQuery.value.trim().toLowerCase())

const searchableValues = (model: ModelOption) => [
  model.label,
  model.value,
  model.canonicalSlug,
  model.description,
  PROVIDER_LABELS[model.provider],
  providerSlug[model.provider],
  model.modality,
  model.contextLength,
  model.maxCompletionTokens,
  ...(model.inputModalities ?? []),
  ...(model.outputModalities ?? []),
  ...(model.supportedParameters ?? [])
]

const matchesSearchQuery = (model: ModelOption, query: string) =>
  searchableValues(model)
    .some(value => String(value ?? '').toLowerCase().includes(query))

const filteredModels = computed(() => {
  const providerFilteredModels = providerFilter.value === 'ALL'
    ? sortedModels.value
    : sortedModels.value.filter(model => model.provider === providerFilter.value)

  if (!normalizedSearchQuery.value) return providerFilteredModels
  return providerFilteredModels.filter(model => matchesSearchQuery(model, normalizedSearchQuery.value))
})

const PAGE_SIZE = 30
const currentPage = ref(1)

watch([providerFilter, normalizedSearchQuery], () => {
  currentPage.value = 1
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredModels.value.length / PAGE_SIZE)))
const paginatedModels = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filteredModels.value.slice(start, start + PAGE_SIZE)
})

const formatPrice = (value?: string | null) => {
  const formatted = formatRatePerMillion(value)
  if (formatted == null) return '—'
  return `${formatted} ${t('models.perMillionTokens')}`
}

const handleCardKeydown = (event: KeyboardEvent, model: ModelOption) => {
  // Avoid retriggering on key auto-repeat; Space activation should fire on
  // keyup, but Enter conventionally activates on keydown like a native button.
  if (event.repeat) return
  if (event.key === 'Enter') {
    event.preventDefault()
    void useModel(model)
  } else if (event.key === ' ') {
    // Prevent the default page scroll while the user holds Space.
    event.preventDefault()
  }
}

const handleCardKeyup = (event: KeyboardEvent, model: ModelOption) => {
  if (event.key === ' ') {
    event.preventDefault()
    void useModel(model)
  }
}

const modelSlug = (model: ModelOption) => {
  // Prefer the canonical slug from the backend if it already contains a provider prefix.
  if (model.canonicalSlug && model.canonicalSlug.includes('/')) return model.canonicalSlug
  if (model.value.includes('/')) return model.value
  return `${providerSlug[model.provider]}/${model.value}`
}

const useModel = async (model: ModelOption) => {
  if (isModelLocked(model)) {
    // Plan-gated model — route the user to the upgrade/pricing page
    // instead of starting a chat (see FE-5).
    await router.push('/pricing')
    return
  }

  try {
    const conversation =
      activeConversation.value || {
        session: await startChat(t('models.chatWithModel', { label: model.label })),
        model: null
      }

    setModelForChat(conversation.session.chatId, model)
    toast.add({
      title: t('models.useModelToastTitle', { label: model.label }),
      description: t('models.useModelToastDescription', { provider: PROVIDER_LABELS[model.provider] }),
      color: 'primary'
    })
    await router.push('/chat')
  } catch (err) {
    toast.add({
      title: t('models.useModelFailedTitle'),
      description: tt(err, 'models.useModelFailedDescription'),
      color: 'error'
    })
  }
}

const handleExpertModeUpdate = async (value: boolean | 'indeterminate') => {
  settings.value.expertMode = value === true
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

onMounted(async () => {
  try {
    await checkAuth()
    await ensureModels()
  } catch (err) {
    console.error(err)
    toast.add({
      title: t('models.loadCatalogFailed'),
      description: tt(err, 'models.backendNotReachable'),
      color: 'error'
    })
  }
})
</script>

<template>
  <div>
    <UDashboardPanel>
      <template #body>
        <UContainer class="py-10 max-w-6xl">
          <UAlert
            v-if="modelsError"
            color="error"
            icon="i-heroicons-exclamation-triangle"
            :description="modelsError"
            class="mb-8"
          />

          <!-- Page heading -->
          <header class="mb-8 space-y-3">
            <h1 class="font-serif-display text-4xl sm:text-5xl text-gray-900 dark:text-gray-50">
              {{ t('models.title') }}
            </h1>
            <p class="text-base text-gray-500 dark:text-gray-300 max-w-2xl">
              {{ t('models.pageIntroLine1') }}<br>
              {{ t('models.pageIntroLine2') }}
            </p>
          </header>

          <!-- Catalogue controls -->
          <div class="mb-8 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <UInput
              v-model="searchQuery"
              type="search"
              icon="i-heroicons-magnifying-glass"
              :placeholder="t('models.searchPlaceholder')"
              :aria-label="t('models.searchPlaceholder')"
              class="w-full lg:max-w-md"
            />

            <div class="flex flex-col gap-3">
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="filter in providerFilters"
                  :key="filter.value"
                  type="button"
                  :aria-pressed="providerFilter === filter.value"
                  class="filter-pill"
                  :class="providerFilter === filter.value ? 'filter-pill-active' : 'filter-pill-inactive'"
                  @click="providerFilter = filter.value"
                >
                  {{ filter.label }}
                </button>
              </div>
              <div
                v-if="isAuthenticated"
                class="flex items-center rounded-lg border border-gray-200 px-3 py-2 dark:border-gray-700"
              >
                <UCheckbox
                  :model-value="settings.expertMode"
                  :label="t('models.expertMode')"
                  :description="t('models.expertModeDescription')"
                  :disabled="expertModeLoading"
                  @update:model-value="handleExpertModeUpdate"
                />
              </div>
            </div>
          </div>

          <!-- Loading skeletons -->
          <div v-if="modelsLoading && !models.length" class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            <div v-for="index in 6" :key="index" class="skeleton-shimmer h-44" />
          </div>

          <UEmpty
            v-else-if="!filteredModels.length"
            icon="i-heroicons-cpu-chip"
            :title="t('models.noModelsFoundTitle')"
            :description="t('models.noModelsFoundDescriptionShort')"
            class="animate-fade-in-up"
          />

          <!-- Model grid -->
          <div v-else class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3 stagger-children">
            <div
              v-for="model in paginatedModels"
              :key="`${model.provider}:${model.value}`"
              class="model-card"
              :class="{ 'model-card-locked': isModelLocked(model) }"
              role="button"
              tabindex="0"
              :aria-label="isModelLocked(model)
                ? t('models.premiumRequiredAria', { label: model.label })
                : t('models.chatWithModel', { label: model.label })"
              @click="useModel(model)"
              @keydown="handleCardKeydown($event, model)"
              @keyup="handleCardKeyup($event, model)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="flex items-start gap-3 min-w-0">
                  <div
                    class="provider-avatar"
                    :class="`provider-avatar-${model.provider.toLowerCase()}`"
                    aria-hidden="true"
                  >
                    {{ providerInitial[model.provider] }}
                  </div>
                  <div class="min-w-0">
                    <div class="text-base font-semibold text-gray-900 dark:text-gray-50 truncate flex items-center gap-1.5">
                      <span class="truncate">{{ model.label }}</span>
                      <UTooltip
                        v-if="isModelLocked(model)"
                        :text="t('models.premiumRequired')"
                      >
                        <UIcon
                          name="i-heroicons-lock-closed"
                          class="size-4 text-gray-500 dark:text-gray-300 flex-shrink-0"
                        />
                      </UTooltip>
                    </div>
                    <div class="text-xs font-mono text-gray-500 dark:text-gray-300 truncate">
                      {{ modelSlug(model) }}
                    </div>
                  </div>
                </div>

                <button
                  type="button"
                  class="favorite-btn"
                  :class="{ 'favorite-btn-active': isFavorite(model) }"
                  :aria-label="isFavorite(model) ? t('models.removeFromFavorites') : t('models.addToFavorites')"
                  @click.stop="toggleFavorite(model)"
                >
                  <UIcon
                    :name="isFavorite(model) ? 'i-heroicons-star-solid' : 'i-heroicons-star'"
                    class="w-5 h-5"
                  />
                </button>
              </div>

              <div class="mt-5 pt-4 border-t border-gray-100 dark:border-gray-800/60 space-y-2">
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-300">{{ t('models.inputLabel') }}</span>
                  <span class="font-mono text-gray-700 dark:text-gray-200">{{ formatPrice(model.promptPrice) }}</span>
                </div>
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-300">{{ t('models.outputLabel') }}</span>
                  <span class="font-mono text-gray-700 dark:text-gray-200">{{ formatPrice(model.completionPrice) }}</span>
                </div>
              </div>
            </div>
          </div>

          <div v-if="totalPages > 1" class="flex justify-center pt-8">
            <UPagination v-model:page="currentPage" :total="filteredModels.length" :items-per-page="PAGE_SIZE" />
          </div>
        </UContainer>
      </template>
    </UDashboardPanel>
  </div>
</template>

<style scoped>
/* ── Filter pills ────────────────────────────────────────────────────── */
.filter-pill {
  padding: 0.4rem 1rem;
  border-radius: 9999px;
  font-size: 0.875rem;
  font-weight: 500;
  transition:
    background-color var(--duration-fast, 120ms) var(--ease-smooth, ease),
    color var(--duration-fast, 120ms) var(--ease-smooth, ease),
    border-color var(--duration-fast, 120ms) var(--ease-smooth, ease);
  cursor: pointer;
  border: 1px solid transparent;
}
.filter-pill-active {
  background: var(--color-gray-900);
  color: var(--color-gray-50);
  border-color: var(--color-gray-900);
}
:global(.dark) .filter-pill-active {
  background: var(--color-gray-700);
  color: var(--color-gray-100);
  border-color: var(--color-gray-600);
}
.filter-pill-inactive {
  background: transparent;
  color: var(--color-gray-700);
  border-color: var(--color-gray-200);
}
.filter-pill-inactive:hover {
  background: rgba(0, 0, 0, 0.03);
  border-color: var(--color-gray-300);
}
:global(.dark) .filter-pill-inactive {
  color: var(--color-gray-200);
  border-color: var(--color-gray-600);
}
:global(.dark) .filter-pill-inactive:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: var(--color-gray-500);
}

/* ── Model card ──────────────────────────────────────────────────────── */
.model-card {
  background: color-mix(in oklab, var(--paper-2) 92%, var(--ink) 4%);
  border: 1px solid var(--hair);
  border-radius: 0.875rem;
  padding: 1.25rem;
  transition:
    border-color var(--duration-fast, 120ms) var(--ease-smooth, ease),
    transform var(--duration-fast, 120ms) var(--ease-smooth, ease),
    box-shadow var(--duration-fast, 120ms) var(--ease-smooth, ease);
  cursor: pointer;
}
.model-card:hover {
  border-color: color-mix(in oklab, var(--hair) 65%, var(--ink) 25%);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px color-mix(in oklab, var(--ink) 8%, transparent);
}
.model-card:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: 2px;
}
:global(.dark) .model-card {
  background: color-mix(in oklab, var(--paper-2) 84%, var(--ink) 8%);
  border-color: color-mix(in oklab, var(--hair) 82%, var(--ink) 8%);
}
:global(.dark) .model-card:hover {
  border-color: color-mix(in oklab, var(--hair) 68%, var(--ink) 18%);
  box-shadow: 0 4px 14px color-mix(in oklab, black 30%, transparent);
}

/* Plan-locked model cards: mute the card so users can still see the
   information but understand the model is not available on their plan. */
.model-card-locked {
  opacity: 0.7;
}
.model-card-locked:hover {
  opacity: 0.85;
}

/* ── Provider avatar ─────────────────────────────────────────────────── */
.provider-avatar {
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 0.5rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 0.95rem;
  font-weight: 600;
  flex-shrink: 0;
  font-family: var(--font-serif);
}
.provider-avatar-openai {
  background: rgba(184, 46, 142, 0.10);
  color: #B82E8E;
}
.provider-avatar-google {
  background: rgba(184, 134, 11, 0.12);
  color: #8A6410;
}
.provider-avatar-claude {
  background: rgba(214, 124, 84, 0.14);
  color: #BC5A2E;
}
.provider-avatar-openrouter {
  background: rgba(100, 116, 139, 0.14);
  color: var(--color-gray-700);
}
:global(.dark) .provider-avatar-openai {
  background: rgba(229, 51, 138, 0.16);
  color: #F388C0;
}
:global(.dark) .provider-avatar-google {
  background: rgba(214, 168, 84, 0.16);
  color: #E8C27A;
}
:global(.dark) .provider-avatar-claude {
  background: rgba(214, 124, 84, 0.20);
  color: #E89A6B;
}
:global(.dark) .provider-avatar-openrouter {
  background: rgba(148, 163, 184, 0.16);
  color: var(--color-gray-300);
}

/* ── Favorite button ─────────────────────────────────────────────────── */
.favorite-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.375rem;
  border-radius: 0.5rem;
  color: var(--color-gray-300);
  transition:
    color var(--duration-fast, 120ms) var(--ease-smooth, ease),
    background-color var(--duration-fast, 120ms) var(--ease-smooth, ease);
  cursor: pointer;
  flex-shrink: 0;
}
.favorite-btn:hover {
  background: rgba(0, 0, 0, 0.04);
  color: var(--color-gray-500);
}
:global(.dark) .favorite-btn {
  color: var(--color-gray-300);
}
:global(.dark) .favorite-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--color-gray-200);
}
.favorite-btn-active {
  color: var(--ui-primary);
}
.favorite-btn-active:hover {
  color: var(--ui-primary);
}
</style>
