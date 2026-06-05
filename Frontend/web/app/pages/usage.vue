<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

definePageMeta({
  layout: 'default'
})

type UsageItem = {
  provider: string
  modelId: string
  calls: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  costMicroCents: number
}

type DailyUsageItem = {
  day: string
  calls: number
  totalTokens: number
  costMicroCents: number
}

type UsageResponse = {
  since: string
  until: string
  totalCalls: number
  totalTokens: number
  totalCostMicroCents: number
  items: UsageItem[]
  daily: DailyUsageItem[]
}

const { t, locale } = useI18n()
const tt = useTranslatedError()
const { requestJson } = useChatApi()

const rangeDays = ref(30)
const modelFilter = ref('')
const usage = ref<UsageResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const rangeOptions = computed(() => [
  { label: t('usage.range7'), value: 7 },
  { label: t('usage.range30'), value: 30 },
  { label: t('usage.range90'), value: 90 }
])

const fromDate = computed(() => {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() - rangeDays.value)
  return date.toISOString().slice(0, 10)
})

const toDate = computed(() => new Date().toISOString().slice(0, 10))

const topModel = computed(() => usage.value?.items?.[0] ?? null)
const maxModelTokens = computed(() =>
  Math.max(1, ...(usage.value?.items ?? []).map(item => item.totalTokens))
)
const maxDailyTokens = computed(() =>
  Math.max(1, ...(usage.value?.daily ?? []).map(item => item.totalTokens))
)

const loadUsage = async () => {
  loading.value = true
  error.value = null
  try {
    const params = new URLSearchParams({
      from: fromDate.value,
      to: toDate.value
    })
    const model = modelFilter.value.trim()
    if (model) params.set('model', model)
    usage.value = await requestJson<UsageResponse>(`/usage?${params}`, { method: 'GET' })
  } catch (err) {
    error.value = tt(err)
  } finally {
    loading.value = false
  }
}

const selectRange = async (days: number) => {
  rangeDays.value = days
  await loadUsage()
}

const applyFilters = async () => {
  await loadUsage()
}

const formatTokens = (value: number | null | undefined) => {
  const count = value ?? 0
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`
  return count.toLocaleString(locale.value)
}

const formatCost = (microCents: number | null | undefined) => {
  const usd = (microCents ?? 0) / 100_000_000
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: usd < 1 ? 4 : 2,
    maximumFractionDigits: usd < 1 ? 4 : 2
  }).format(usd)
}

const formatDay = (day: string) => {
  const date = new Date(`${day}T00:00:00Z`)
  if (Number.isNaN(date.getTime())) return day
  return date.toLocaleDateString(locale.value, { month: 'short', day: 'numeric' })
}

onMounted(loadUsage)
</script>

<template>
  <div>
    <UDashboardPanel>
      <template #body>
        <UContainer class="py-10 max-w-6xl space-y-8">
        <header class="space-y-3">
          <h1 class="font-serif-display text-4xl sm:text-5xl text-gray-900 dark:text-gray-50">
            {{ t('usage.title') }}
          </h1>
          <p class="text-base text-gray-500 dark:text-gray-400 max-w-2xl">
            {{ t('usage.subtitle') }}
          </p>
        </header>

        <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div class="flex flex-wrap gap-2">
            <UButton
              v-for="option in rangeOptions"
              :key="option.value"
              :label="option.label"
              :color="rangeDays === option.value ? 'primary' : 'neutral'"
              :variant="rangeDays === option.value ? 'soft' : 'ghost'"
              @click="selectRange(option.value)"
            />
          </div>
          <form class="flex w-full gap-2 sm:w-auto" @submit.prevent="applyFilters">
            <UInput
              v-model="modelFilter"
              class="min-w-0 sm:w-72"
              icon="i-heroicons-magnifying-glass"
              :placeholder="t('usage.modelFilterPlaceholder')"
            />
            <UButton
              type="submit"
              icon="i-heroicons-funnel"
              :label="t('usage.applyFilters')"
              :loading="loading"
            />
          </form>
        </div>

        <UAlert
          v-if="error"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :title="t('usage.loadFailedTitle')"
          :description="error"
        />

        <div class="grid gap-4 sm:grid-cols-3">
          <UCard>
            <div class="space-y-1">
              <p class="text-sm text-gray-500 dark:text-gray-400">{{ t('usage.totalTokens') }}</p>
              <p class="text-2xl font-semibold text-gray-900 dark:text-gray-50">
                {{ formatTokens(usage?.totalTokens) }}
              </p>
            </div>
          </UCard>
          <UCard>
            <div class="space-y-1">
              <p class="text-sm text-gray-500 dark:text-gray-400">{{ t('usage.estimatedCost') }}</p>
              <p class="text-2xl font-semibold text-gray-900 dark:text-gray-50">
                {{ formatCost(usage?.totalCostMicroCents) }}
              </p>
            </div>
          </UCard>
          <UCard>
            <div class="space-y-1">
              <p class="text-sm text-gray-500 dark:text-gray-400">{{ t('usage.topModel') }}</p>
              <p class="truncate text-2xl font-semibold text-gray-900 dark:text-gray-50">
                {{ topModel?.modelId || t('common.unknown') }}
              </p>
            </div>
          </UCard>
        </div>

        <div class="grid gap-4 lg:grid-cols-[1.3fr_1fr]">
          <UCard>
            <div class="space-y-5">
              <div class="flex items-center justify-between gap-4">
                <h2 class="text-lg font-semibold text-gray-900 dark:text-gray-50">
                  {{ t('usage.tokensByModel') }}
                </h2>
                <UBadge variant="soft" color="neutral" :label="t('usage.requestsCount', { count: usage?.totalCalls ?? 0 })" />
              </div>

              <div v-if="loading" class="space-y-3">
                <div class="skeleton-shimmer h-10" />
                <div class="skeleton-shimmer h-10" />
                <div class="skeleton-shimmer h-10" />
              </div>
              <div v-else-if="!usage?.items?.length" class="py-8 text-sm text-gray-500 dark:text-gray-400">
                {{ t('usage.noData') }}
              </div>
              <div v-else class="space-y-3">
                <div v-for="item in usage.items" :key="`${item.provider}:${item.modelId}`" class="usage-model-row">
                  <div class="min-w-0">
                    <div class="truncate text-sm font-medium text-gray-900 dark:text-gray-50">
                      {{ item.modelId }}
                    </div>
                    <div class="text-xs text-gray-500 dark:text-gray-400">
                      {{ item.provider }} · {{ t('usage.requestsCount', { count: item.calls }) }}
                    </div>
                  </div>
                  <div class="usage-bar-track">
                    <div class="usage-bar-fill" :style="{ width: `${Math.max(4, (item.totalTokens / maxModelTokens) * 100)}%` }" />
                  </div>
                  <div class="text-right text-sm font-medium text-gray-900 dark:text-gray-50">
                    {{ formatTokens(item.totalTokens) }}
                  </div>
                </div>
              </div>
            </div>
          </UCard>

          <UCard>
            <div class="space-y-5">
              <h2 class="text-lg font-semibold text-gray-900 dark:text-gray-50">
                {{ t('usage.dailyTrend') }}
              </h2>
              <div v-if="loading" class="skeleton-shimmer h-56" />
              <div v-else-if="!usage?.daily?.length" class="py-8 text-sm text-gray-500 dark:text-gray-400">
                {{ t('usage.noData') }}
              </div>
              <div v-else class="usage-daily-chart">
                <div
                  v-for="item in usage.daily"
                  :key="item.day"
                  class="usage-daily-column"
                  :title="`${formatDay(item.day)} · ${formatTokens(item.totalTokens)}`"
                >
                  <div class="usage-daily-bar" :style="{ height: `${Math.max(6, (item.totalTokens / maxDailyTokens) * 100)}%` }" />
                  <span>{{ formatDay(item.day) }}</span>
                </div>
              </div>
            </div>
          </UCard>
        </div>
        </UContainer>
      </template>
    </UDashboardPanel>
  </div>
</template>

<style scoped>
.usage-model-row {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(80px, 1.4fr) auto;
  gap: 0.75rem;
  align-items: center;
}

.usage-bar-track {
  height: 0.625rem;
  border-radius: 999px;
  overflow: hidden;
  background: color-mix(in oklab, var(--paper-2) 75%, var(--ink) 10%);
}

.usage-bar-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #0ea5e9, #22c55e);
}

.usage-daily-chart {
  height: 14rem;
  display: flex;
  align-items: end;
  gap: 0.45rem;
  padding-top: 1rem;
}

.usage-daily-column {
  min-width: 1.5rem;
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: end;
  gap: 0.5rem;
  color: rgb(107 114 128);
  font-size: 0.7rem;
}

.usage-daily-bar {
  width: 100%;
  max-width: 2rem;
  border-radius: 0.375rem 0.375rem 0 0;
  background: linear-gradient(180deg, #22c55e, #0ea5e9);
}

@media (max-width: 640px) {
  .usage-model-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .usage-daily-chart {
    overflow-x: auto;
    gap: 0.25rem;
  }

  .usage-daily-column {
    min-width: 1.25rem;
    font-size: 0.625rem;
  }

  .usage-daily-bar {
    max-width: 1.5rem;
  }
}
</style>
