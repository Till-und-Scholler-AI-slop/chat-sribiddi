<script setup lang="ts">
import type { ModelOption, Provider } from '~/composables/useChatApi'
import { PROVIDERS } from '~/composables/useChatApi'

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
  { label: 'All providers', value: 'ALL' as const },
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
  value == null ? 'Unknown' : `${formatInteger(value)} tokens`

const toPriceLabel = (value?: string | null, suffix = '/ 1M tokens') => {
  if (!value) return '—'
  const numeric = Number(value)
  if (Number.isNaN(numeric)) return value
  const perMillion = numeric * 1_000_000
  const decimals = numeric === 0 ? 0 : perMillion >= 1 ? 2 : 4
  return `$${perMillion.toFixed(decimals)} ${suffix}`
}

const toRequestPriceLabel = (value?: string | null) => {
  if (!value) return '—'
  const numeric = Number(value)
  if (Number.isNaN(numeric)) return value
  return `$${numeric.toFixed(numeric === 0 ? 0 : numeric >= 1 ? 2 : 4)} / request`
}

const formatCreated = (value?: number | null) => {
  if (!value) return 'Unknown'
  return new Date(value * 1000).toLocaleDateString()
}

const useModel = async (model: ModelOption) => {
  try {
    const conversation =
      activeConversation.value || {
        session: await startChat(`Chat with ${model.label}`),
        model: null
      }

    setModelForChat(conversation.session.chatId, model)
    toast.add({
      title: `${model.label} selected`,
      description: `Using ${providerLabels[model.provider]} for the current chat.`,
      color: 'primary'
    })
    await router.push('/chat')
  } catch (err) {
    toast.add({
      title: 'Could not apply model',
      description: err instanceof Error ? err.message : 'Unable to open chat.',
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
      title: 'Could not load model catalog',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  }
})
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar
        title="Model Catalog"
        :badge="modelsLoading ? 'Loading…' : `${filteredModels.length} models`"
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

        <UCard>
          <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div class="space-y-1">
              <div class="text-lg font-semibold">Browse available models</div>
              <p class="text-sm text-gray-500 dark:text-gray-400">
                Compare providers, pricing, and context windows before choosing a model for chat.
              </p>
            </div>

            <div class="flex flex-col gap-3 sm:flex-row">
              <UInput
                v-model="search"
                icon="i-heroicons-magnifying-glass"
                placeholder="Search models, providers, or parameters"
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
          <USkeleton v-for="index in 6" :key="index" class="h-56" />
        </div>

        <UEmptyState
          v-else-if="!filteredModels.length"
          icon="i-heroicons-cpu-chip"
          title="No models found"
          description="Try a different provider filter, or add an API key to load live provider catalogs."
        />

        <div v-else class="grid gap-4 xl:grid-cols-2">
          <UCard
            v-for="model in paginatedModels"
            :key="`${model.provider}:${model.value}`"
            class="h-full"
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
                    Canonical: {{ model.canonicalSlug }}
                  </div>
                </div>

                <div class="flex items-center gap-2 flex-shrink-0">
                  <UButton
                    :icon="isFavorite(model) ? 'i-heroicons-star-solid' : 'i-heroicons-star'"
                    :color="isFavorite(model) ? 'warning' : 'neutral'"
                    variant="ghost"
                    size="sm"
                    :aria-label="isFavorite(model) ? 'Remove from favorites' : 'Add to favorites'"
                    @click="toggleFavorite(model)"
                  />
                  <UButton
                    color="primary"
                    variant="soft"
                    size="sm"
                    icon="i-heroicons-arrow-right-circle"
                    label="Use model"
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
                    Context window
                  </div>
                  <div class="mt-1 font-medium">{{ formatTokenWindow(model.contextLength) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Max output
                  </div>
                  <div class="mt-1 font-medium">{{ formatTokenWindow(model.maxCompletionTokens) }}</div>
                </div>
              </div>

              <div class="grid gap-3 sm:grid-cols-2">
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Prompt price
                  </div>
                  <div class="mt-1 font-medium">{{ toPriceLabel(model.promptPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Completion price
                  </div>
                  <div class="mt-1 font-medium">{{ toPriceLabel(model.completionPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Request price
                  </div>
                  <div class="mt-1 font-medium">{{ toRequestPriceLabel(model.requestPrice) }}</div>
                </div>
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Released
                  </div>
                  <div class="mt-1 font-medium">{{ formatCreated(model.created) }}</div>
                </div>
              </div>

              <div class="grid gap-4 lg:grid-cols-2">
                <div class="space-y-2">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Architecture
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
                      :label="`In: ${item}`"
                      variant="soft"
                      color="neutral"
                    />
                    <UBadge
                      v-for="item in model.outputModalities || []"
                      :key="`output-${item}`"
                      :label="`Out: ${item}`"
                      variant="soft"
                      color="neutral"
                    />
                  </div>
                </div>

                <div class="space-y-2">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Supported parameters
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
                      +{{ (model.supportedParameters || []).length - 8 }} more
                    </span>
                    <span
                      v-if="!(model.supportedParameters || []).length"
                      class="text-sm text-gray-500 dark:text-gray-400"
                    >
                      No parameter metadata available.
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
</template>
