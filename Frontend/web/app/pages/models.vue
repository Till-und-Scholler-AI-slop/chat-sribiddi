<script setup lang="ts">
import type { ModelOption, ModelPricing, Provider } from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const {
  activeChatId,
  activeConversation,
  ensureModels,
  models,
  modelsError,
  modelsLoading,
  preferredModel,
  setModelForChat,
  setPreferredModel
} = useChatStore()

const toast = useToast()
const search = ref('')
const providerFilter = ref<Provider | 'ALL'>('ALL')

const providerOptions: Array<{ label: string; value: Provider | 'ALL' }> = [
  { label: 'All providers', value: 'ALL' },
  { label: 'OpenAI', value: 'OPENAI' },
  { label: 'Google', value: 'GOOGLE' },
  { label: 'Claude', value: 'CLAUDE' },
  { label: 'OpenRouter', value: 'OPENROUTER' }
]

const providerLabel = (provider: Provider) =>
  provider === 'OPENAI'
    ? 'OpenAI'
    : provider === 'GOOGLE'
      ? 'Google'
      : provider === 'CLAUDE'
        ? 'Claude'
        : 'OpenRouter'

const filteredModels = computed(() => {
  const query = search.value.trim().toLowerCase()
  return models.value.filter(model => {
    if (providerFilter.value !== 'ALL' && model.provider !== providerFilter.value) {
      return false
    }

    if (!query) {
      return true
    }

    return [model.label, model.value, model.description || '', providerLabel(model.provider)]
      .join(' ')
      .toLowerCase()
      .includes(query)
  })
})

const selectedForCurrentChat = computed<ModelOption | null>(() => activeConversation.value?.model || null)

const isPreferredModel = (model: ModelOption) =>
  preferredModel.value?.value === model.value && preferredModel.value?.provider === model.provider

const isCurrentChatModel = (model: ModelOption) =>
  selectedForCurrentChat.value?.value === model.value &&
  selectedForCurrentChat.value?.provider === model.provider

const formatContextWindow = (value?: number | null) => {
  if (value == null) return 'Unavailable'
  return `${value.toLocaleString()} tokens`
}

const formatUsdRate = (value?: string | null, unit = 'token') => {
  if (!value) return null

  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return `$${value}/${unit}`

  const perMillion = numeric * 1_000_000
  if (perMillion >= 0.01) {
    return `$${perMillion.toFixed(perMillion >= 1 ? 2 : 4)}/1M ${unit}s`
  }

  return `$${numeric.toPrecision(3)}/${unit}`
}

const pricingRows = (pricing?: ModelPricing | null) => {
  if (!pricing) return []
  return [
    { label: 'Input', value: formatUsdRate(pricing.prompt) },
    { label: 'Output', value: formatUsdRate(pricing.completion) },
    { label: 'Image', value: formatUsdRate(pricing.image, 'image') },
    { label: 'Request', value: formatUsdRate(pricing.request, 'request') }
  ].filter((entry): entry is { label: string; value: string } => Boolean(entry.value))
}

const handleUseInCurrentChat = (model: ModelOption) => {
  if (!activeChatId.value) {
    toast.add({
      title: 'No active chat',
      description: 'Open or start a chat first to apply a model immediately.',
      color: 'warning'
    })
    return
  }

  setModelForChat(activeChatId.value, model)
  toast.add({
    title: 'Model selected',
    description: `${model.label} will be used for the current chat.`,
    color: 'primary'
  })
}

const handleSetPreferredModel = (model: ModelOption) => {
  setPreferredModel(model)
  toast.add({
    title: 'Preferred model saved',
    description: `${model.label} is now the default for new chats.`,
    color: 'primary'
  })
}

onMounted(async () => {
  try {
    await ensureModels()
  } catch (err) {
    toast.add({
      title: 'Could not load models',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  }
})
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar title="Models" :badge="modelsLoading ? 'Refreshing…' : undefined" />
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
          <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <div class="space-y-1">
              <div class="text-lg font-semibold">Browse model catalog</div>
              <p class="text-sm text-gray-500 dark:text-gray-400">
                Inspect pricing, context windows, and pick the model you want to use next.
              </p>
            </div>

            <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
              <UInput
                v-model="search"
                icon="i-heroicons-magnifying-glass"
                placeholder="Search by model, provider, or description"
                class="min-w-72"
              />
              <USelect
                v-model="providerFilter"
                :items="providerOptions"
                value-key="value"
                option-attribute="label"
                class="w-48"
              />
            </div>
          </div>
        </UCard>

        <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <UCard
            v-for="model in filteredModels"
            :key="`${model.provider}-${model.value}`"
            class="h-full"
          >
            <div class="flex h-full flex-col gap-4">
              <div class="flex items-start justify-between gap-3">
                <div class="space-y-1 min-w-0">
                  <div class="text-base font-semibold truncate">{{ model.label }}</div>
                  <p class="text-sm text-gray-500 dark:text-gray-400 break-all">{{ model.value }}</p>
                </div>
                <div class="flex flex-wrap items-center justify-end gap-2">
                  <UBadge :label="providerLabel(model.provider)" variant="soft" color="neutral" />
                  <UBadge
                    v-if="isPreferredModel(model)"
                    label="Preferred"
                    variant="soft"
                    color="primary"
                  />
                  <UBadge
                    v-if="isCurrentChatModel(model)"
                    label="Current chat"
                    variant="soft"
                    color="primary"
                  />
                </div>
              </div>

              <p v-if="model.description" class="text-sm text-gray-600 dark:text-gray-300">
                {{ model.description }}
              </p>

              <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Context window
                  </div>
                  <div class="mt-1 text-sm font-medium">{{ formatContextWindow(model.contextWindow) }}</div>
                </div>

                <div class="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  <div class="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
                    Pricing
                  </div>
                  <div v-if="pricingRows(model.pricing).length" class="mt-1 space-y-1 text-sm">
                    <div
                      v-for="entry in pricingRows(model.pricing)"
                      :key="entry.label"
                      class="flex items-center justify-between gap-3"
                    >
                      <span class="text-gray-500 dark:text-gray-400">{{ entry.label }}</span>
                      <span>{{ entry.value }}</span>
                    </div>
                  </div>
                  <div v-else class="mt-1 text-sm text-gray-500 dark:text-gray-400">
                    Pricing unavailable
                  </div>
                </div>
              </div>

              <div class="mt-auto flex flex-wrap items-center gap-2 pt-2">
                <UButton
                  type="button"
                  color="primary"
                  variant="soft"
                  icon="i-heroicons-star"
                  :label="isPreferredModel(model) ? 'Preferred model' : 'Set as preferred'"
                  :disabled="isPreferredModel(model)"
                  @click="handleSetPreferredModel(model)"
                />
                <UButton
                  type="button"
                  variant="outline"
                  color="neutral"
                  icon="i-heroicons-chat-bubble-left-right"
                  :label="activeChatId ? 'Use in current chat' : 'Open a chat to apply now'"
                  :disabled="!activeChatId || isCurrentChatModel(model)"
                  @click="handleUseInCurrentChat(model)"
                />
              </div>
            </div>
          </UCard>
        </div>

        <UCard v-if="!modelsLoading && filteredModels.length === 0">
          <div class="flex items-center gap-3 text-sm text-gray-500 dark:text-gray-400">
            <UIcon name="i-heroicons-cpu-chip" class="size-5" />
            <span>Try a broader search, switch providers, or add an API key in Settings.</span>
          </div>
        </UCard>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
