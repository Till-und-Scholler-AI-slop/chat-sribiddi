<script setup lang="ts">
import type { ModelOption, Provider } from '~/composables/useChatApi'
import { PROVIDERS } from '~/composables/useChatApi'
import { formatRatePerMillion } from '~/composables/chat-helpers'

const { t } = useI18n()

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

const search = ref('')
const providerFilter = ref<'ALL' | Provider>('ALL')

const providerLabels: Record<Provider, string> = {
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  CLAUDE: 'Claude',
  OPENROUTER: 'OpenRouter'
}

const providerBadgeColor = (provider: Provider) =>
  provider === 'OPENROUTER' ? 'primary' : 'neutral'

const providerFilters = computed(() => [
  { label: t('models.providerFilterAll'), value: 'ALL' as const },
  ...PROVIDERS.map(provider => ({
    label: providerLabels[provider],
    value: provider
  }))
])

const searchableText = (model: ModelOption) =>
  [
    model.label,
    model.value,
    model.description,
    model.canonicalSlug,
    providerLabels[model.provider],
    ...(model.supportedParameters || [])
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()

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
  const query = search.value.trim().toLowerCase()
  return sortedModels.value.filter(model => {
    if (providerFilter.value !== 'ALL' && model.provider !== providerFilter.value) {
      return false
    }
    if (!query) return true
    return searchableText(model).includes(query)
  })
})

const PAGE_SIZE = 24
const currentPage = ref(1)

// Reset to first page when filters change
watch([search, providerFilter], () => {
  currentPage.value = 1
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredModels.value.length / PAGE_SIZE)))

const paginatedModels = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filteredModels.value.slice(start, start + PAGE_SIZE)
})

const formatInteger = (value?: number | null) => {
  if (value == null || Number.isNaN(value)) return '—'
  return value.toLocaleString()
}

const formatTokenWindow = (value?: number | null) =>
  value == null ? t('common.unknown') : t('models.tokensSuffix', { count: formatInteger(value) })

const toPriceLabel = (value?: string | null, suffix = t('models.perMillionTokens')) => {
  const formatted = formatRatePerMillion(value)
  if (formatted == null) return '—'
  return `${formatted} ${suffix}`
}

const toRequestPriceLabel = (value?: string | null) => {
  if (!value) return '—'
  const numeric = Number(value)
  if (Number.isNaN(numeric)) return value
  return `$${numeric.toFixed(numeric === 0 ? 0 : numeric >= 1 ? 2 : 4)} ${t('models.perRequest')}`
}

const formatCreated = (value?: number | null) => {
  if (!value) return t('common.unknown')
  return new Date(value * 1000).toLocaleDateString()
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
      description: err instanceof Error ? err.message : t('models.useModelFailedDescription'),
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
      description: err instanceof Error ? err.message : t('models.backendNotReachable'),
      color: 'error'
    })
  }
})
</script>

<template>
  <div>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar
        :title="t('models.title')"
        :badge="modelsLoading ? t('models.loading') : t('models.modelsCount', { count: filteredModels.length })"
      />
    </template>

    <template #body>
      <UContainer class="py-6 space-y-6">
        <UAlert
          v-if="modelsError"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :description="modelsError"
        />

        <UCard class="card-elevated">
          <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div class="space-y-1">
              <div class="text-lg font-semibold tracking-tight">{{ t('models.browseHeading') }}</div>
              <p class="text-sm text-gray-500 dark:text-gray-400">
                {{ t('models.browseDescription') }}
              </p>
            </div>

            <div class="flex flex-col gap-3 sm:flex-row">
              <UInput
                v-model="search"
                icon="i-heroicons-magnifying-glass"
                :placeholder="t('models.searchPlaceholder')"
                class="min-w-[18rem]"
              />
              <USelectMenu
                v-model="providerFilter"
                :items="providerFilters"
                value-key="value"
                label-key="label"
                class="min-w-[13rem]"
              />
            </div>
          </div>
        </UCard>

        <div v-if="modelsLoading && !models.length" class="grid gap-4 xl:grid-cols-2">
          <div v-for="index in 6" :key="index" class="skeleton-shimmer h-56" />
        </div>

        <UEmptyState
          v-else-if="!filteredModels.length"
          icon="i-heroicons-cpu-chip"
          :title="t('models.noModelsFoundTitle')"
          :description="t('models.noModelsFoundDescription')"
          class="animate-fade-in-up"
        />

        <div v-else class="grid gap-4 xl:grid-cols-2 stagger-children">
          <UCard
            v-for="model in paginatedModels"
            :key="`${model.provider}:${model.value}`"
            class="h-full card-elevated"
          >
            <div class="flex h-full flex-col gap-4">
              <div class="flex items-start justify-between gap-3">
                <div class="space-y-1 min-w-0">
                  <div class="flex items-center gap-2 flex-wrap">
                    <div class="text-base font-semibold">{{ model.label }}</div>
                    <UBadge
                      :label="providerLabels[model.provider]"
                      :color="providerBadgeColor(model.provider)"
                      variant="soft"
                    />
                  </div>
                  <div class="text-xs text-gray-500 dark:text-gray-400 break-all">
                    {{ model.value }}
                  </div>
                  <div
                    v-if="model.canonicalSlug && model.canonicalSlug !== model.value"
                    class="text-xs text-gray-500 dark:text-gray-400 break-all"
                  >
                    {{ t('models.canonical', { value: model.canonicalSlug }) }}
                  </div>
                </div>

                <div class="flex items-center gap-2 flex-shrink-0">
                  <UButton
                    :icon="isFavorite(model) ? 'i-heroicons-star-solid' : 'i-heroicons-star'"
                    :color="isFavorite(model) ? 'warning' : 'neutral'"
                    variant="ghost"
                    size="sm"
                    :aria-label="isFavorite(model) ? t('models.removeFromFavorites') : t('models.addToFavorites')"
                    class="btn-interactive"
                    @click="toggleFavorite(model)"
                  />
                  <UButton
                    color="primary"
                    variant="soft"
                    size="sm"
                    icon="i-heroicons-arrow-right-circle"
                    :label="t('models.useModel')"
                    class="btn-interactive"
                    @click="useModel(model)"
                  />
                </div>
              </div>

              <p
                v-if="model.description"
                class="text-sm leading-6 text-gray-600 dark:text-gray-300"
              >
                {{ model.description }}
              </p>

              <div class="grid gap-3 sm:grid-cols-2">
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.contextWindow') }}
                  </div>
                  <div class="mt-1 font-medium">{{ formatTokenWindow(model.contextLength) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.maxOutput') }}
                  </div>
                  <div class="mt-1 font-medium">{{ formatTokenWindow(model.maxCompletionTokens) }}</div>
                </div>
              </div>

              <div class="grid gap-3 sm:grid-cols-2">
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.promptPrice') }}
                  </div>
                  <div class="mt-1 font-medium">{{ toPriceLabel(model.promptPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.completionPrice') }}
                  </div>
                  <div class="mt-1 font-medium">{{ toPriceLabel(model.completionPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.requestPrice') }}
                  </div>
                  <div class="mt-1 font-medium">{{ toRequestPriceLabel(model.requestPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.released') }}
                  </div>
                  <div class="mt-1 font-medium">{{ formatCreated(model.created) }}</div>
                </div>
              </div>

              <div class="grid gap-4 lg:grid-cols-2">
                <div class="space-y-2">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.architecture') }}
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <UBadge
                      v-if="model.modality"
                      :label="model.modality"
                      variant="outline"
                      color="neutral"
                    />
                    <UBadge
                      v-for="item in model.inputModalities || []"
                      :key="`input-${item}`"
                      :label="t('models.inputModality', { value: item })"
                      variant="soft"
                      color="neutral"
                    />
                    <UBadge
                      v-for="item in model.outputModalities || []"
                      :key="`output-${item}`"
                      :label="t('models.outputModality', { value: item })"
                      variant="soft"
                      color="neutral"
                    />
                  </div>
                </div>

                <div class="space-y-2">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    {{ t('models.supportedParameters') }}
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <UBadge
                      v-for="parameter in (model.supportedParameters || []).slice(0, 8)"
                      :key="parameter"
                      :label="parameter"
                      variant="outline"
                      color="neutral"
                    />
                    <span
                      v-if="(model.supportedParameters || []).length > 8"
                      class="text-xs text-gray-500 dark:text-gray-400"
                    >
                      {{ t('models.moreParameters', { count: (model.supportedParameters || []).length - 8 }) }}
                    </span>
                    <span
                      v-if="!(model.supportedParameters || []).length"
                      class="text-sm text-gray-500 dark:text-gray-400"
                    >
                      {{ t('models.noParameterMetadata') }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </UCard>
        </div>

        <div v-if="totalPages > 1" class="flex justify-center pt-2">
          <UPagination v-model:page="currentPage" :total="filteredModels.length" :items-per-page="PAGE_SIZE" />
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
  </div>
</template>
