<script setup lang="ts">
import type { ModelOption, Provider } from '~/composables/useChatApi'
import { PROVIDERS } from '~/composables/useChatApi'
import { formatRatePerMillion } from '~/composables/chat-helpers'

const { t } = useI18n()
const tt = useTranslatedError()

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
  startChat,
  toggleFavorite
} = useChatStore()

const toast = useToast()
const router = useRouter()

const providerFilter = ref<'ALL' | Provider>('ALL')

const providerLabels: Record<Provider, string> = {
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  CLAUDE: 'Claude',
  OPENROUTER: 'OpenRouter'
}

// First letter shown inside the provider avatar
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
  { label: providerLabels.CLAUDE, value: 'CLAUDE' as const },
  { label: providerLabels.OPENAI, value: 'OPENAI' as const },
  { label: providerLabels.GOOGLE, value: 'GOOGLE' as const },
  { label: providerLabels.OPENROUTER, value: 'OPENROUTER' as const }
])

const sortedModels = computed(() =>
  [...models.value].sort((left, right) => {
    const leftFav = isFavorite(left) ? 0 : 1
    const rightFav = isFavorite(right) ? 0 : 1
    if (leftFav !== rightFav) return leftFav - rightFav
    if (left.provider !== right.provider) {
      return PROVIDERS.indexOf(left.provider) - PROVIDERS.indexOf(right.provider)
    }
    return left.label.localeCompare(right.label)
  })
)

const filteredModels = computed(() => {
  if (providerFilter.value === 'ALL') return sortedModels.value
  return sortedModels.value.filter(model => model.provider === providerFilter.value)
})

const PAGE_SIZE = 30
const currentPage = ref(1)

watch(providerFilter, () => {
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
  try {
    const conversation =
      activeConversation.value || {
        session: await startChat(t('models.chatWithModel', { label: model.label })),
        model: null
      }

    setModelForChat(conversation.session.chatId, model)
    toast.add({
      title: t('models.useModelToastTitle', { label: model.label }),
      description: t('models.useModelToastDescription', { provider: providerLabels[model.provider] }),
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

onMounted(async () => {
  try {
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
            <p class="text-base text-gray-500 dark:text-gray-400 max-w-2xl">
              {{ t('models.pageIntroLine1') }}<br>
              {{ t('models.pageIntroLine2') }}
            </p>
          </header>

          <!-- Provider filter pills -->
          <div class="flex flex-wrap gap-2 mb-8">
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

          <!-- Loading skeletons -->
          <div v-if="modelsLoading && !models.length" class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            <div v-for="index in 6" :key="index" class="skeleton-shimmer h-44" />
          </div>

          <UEmptyState
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
              role="button"
              tabindex="0"
              :aria-label="t('models.chatWithModel', { label: model.label })"
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
                    <div class="text-base font-semibold text-gray-900 dark:text-gray-50 truncate">
                      {{ model.label }}
                    </div>
                    <div class="text-xs font-mono text-gray-500 dark:text-gray-400 truncate">
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
                  <span class="text-gray-500 dark:text-gray-400">{{ t('models.inputLabel') }}</span>
                  <span class="font-mono text-gray-700 dark:text-gray-200">{{ formatPrice(model.promptPrice) }}</span>
                </div>
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-400">{{ t('models.outputLabel') }}</span>
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
  background: var(--color-gray-50);
  color: var(--color-gray-900);
  border-color: var(--color-gray-50);
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
  color: var(--color-gray-300);
  border-color: var(--color-gray-700);
}
:global(.dark) .filter-pill-inactive:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: var(--color-gray-600);
}

/* ── Model card ──────────────────────────────────────────────────────── */
.model-card {
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--color-gray-200);
  border-radius: 0.875rem;
  padding: 1.25rem;
  transition:
    border-color var(--duration-fast, 120ms) var(--ease-smooth, ease),
    transform var(--duration-fast, 120ms) var(--ease-smooth, ease),
    box-shadow var(--duration-fast, 120ms) var(--ease-smooth, ease);
  cursor: pointer;
}
.model-card:hover {
  border-color: var(--color-gray-300);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}
.model-card:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: 2px;
}
:global(.dark) .model-card {
  background: color-mix(in oklab, var(--paper-2) 70%, transparent);
  border-color: var(--color-gray-800);
}
:global(.dark) .model-card:hover {
  border-color: var(--color-gray-700);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
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
  color: var(--color-gray-600);
}
:global(.dark) .favorite-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--color-gray-400);
}
.favorite-btn-active {
  color: var(--ui-primary);
}
.favorite-btn-active:hover {
  color: var(--ui-primary);
}
</style>
