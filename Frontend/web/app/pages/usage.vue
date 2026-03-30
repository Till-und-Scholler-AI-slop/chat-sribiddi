<script setup lang="ts">
import { Bar, Doughnut } from 'vue-chartjs'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  BarElement,
  ArcElement,
  Title,
  Tooltip,
  Legend
} from 'chart.js'
import type { UsageBucket, UsageResponse } from '~/composables/useUsageApi'

ChartJS.register(CategoryScale, LinearScale, BarElement, ArcElement, Title, Tooltip, Legend)

definePageMeta({
  layout: 'default'
})

const { fetchUsage, getExportUrl } = useUsageApi()
const toast = useToast()

const loading = ref(true)
const period = ref<'7' | '30' | '90'>('30')

const dayData = ref<UsageResponse | null>(null)
const modelData = ref<UsageResponse | null>(null)
const providerData = ref<UsageResponse | null>(null)

const periodOptions = [
  { label: '7 days', value: '7' },
  { label: '30 days', value: '30' },
  { label: '90 days', value: '90' }
]

function getFromDate() {
  const d = new Date()
  d.setDate(d.getDate() - Number(period.value))
  return d.toISOString()
}

async function loadData() {
  loading.value = true
  try {
    const from = getFromDate()
    const [day, model, provider] = await Promise.all([
      fetchUsage({ from, groupBy: 'day' }),
      fetchUsage({ from, groupBy: 'model' }),
      fetchUsage({ from, groupBy: 'provider' })
    ])
    dayData.value = day
    modelData.value = model
    providerData.value = provider
  } catch (err) {
    toast.add({
      title: 'Could not load usage data',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    loading.value = false
  }
}

watch(period, () => loadData())
onMounted(() => loadData())

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

function formatCost(n: number): string {
  return `$${n.toFixed(4)}`
}

const CHART_COLORS = [
  '#38bdf8', '#818cf8', '#a78bfa', '#f472b6', '#fb923c',
  '#4ade80', '#facc15', '#22d3ee', '#e879f9', '#f87171'
]

const barChartData = computed(() => {
  const buckets = dayData.value?.buckets ?? []
  return {
    labels: buckets.map(b => b.key),
    datasets: [
      {
        label: 'Input tokens',
        data: buckets.map(b => b.inputTokens),
        backgroundColor: '#38bdf8'
      },
      {
        label: 'Output tokens',
        data: buckets.map(b => b.outputTokens),
        backgroundColor: '#818cf8'
      }
    ]
  }
})

const barChartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { position: 'top' as const, labels: { color: '#9ca3af' } },
    title: { display: true, text: `Token usage per day (last ${period.value} days)`, color: '#9ca3af' }
  },
  scales: {
    x: {
      stacked: true,
      ticks: { color: '#9ca3af', maxRotation: 45 },
      grid: { color: 'rgba(156,163,175,0.1)' }
    },
    y: {
      stacked: true,
      ticks: { color: '#9ca3af' },
      grid: { color: 'rgba(156,163,175,0.1)' }
    }
  }
}))

const modelChartData = computed(() => {
  const buckets = modelData.value?.buckets ?? []
  return {
    labels: buckets.map(b => b.key),
    datasets: [{
      data: buckets.map(b => b.totalTokens),
      backgroundColor: CHART_COLORS.slice(0, buckets.length)
    }]
  }
})

const providerChartData = computed(() => {
  const buckets = providerData.value?.buckets ?? []
  return {
    labels: buckets.map(b => b.key),
    datasets: [{
      data: buckets.map(b => b.totalTokens),
      backgroundColor: CHART_COLORS.slice(0, buckets.length)
    }]
  }
})

const doughnutOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { position: 'bottom' as const, labels: { color: '#9ca3af' } }
  }
}

function handleExport() {
  const url = getExportUrl({ from: getFromDate() })
  window.open(url, '_blank')
}
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar title="Usage Dashboard" />
    </template>

    <template #body>
      <UContainer class="py-8 space-y-8">
        <!-- Controls -->
        <div class="flex items-center justify-between gap-4 flex-wrap">
          <div class="flex items-center gap-3">
            <USelectMenu
              v-model="period"
              :items="periodOptions"
              value-key="value"
              class="w-36"
            />
            <UButton
              variant="outline"
              color="neutral"
              icon="i-heroicons-arrow-path"
              label="Refresh"
              :loading="loading"
              @click="loadData"
            />
          </div>
          <UButton
            variant="outline"
            color="neutral"
            icon="i-heroicons-arrow-down-tray"
            label="Export CSV"
            @click="handleExport"
          />
        </div>

        <!-- Loading -->
        <div v-if="loading" class="space-y-4">
          <USkeleton class="h-14" />
          <USkeleton class="h-64" />
          <USkeleton class="h-48" />
        </div>

        <div v-else class="space-y-8">
          <!-- Summary cards -->
          <div class="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <UCard>
              <div class="text-sm text-gray-500 dark:text-gray-400">Total tokens</div>
              <div class="text-2xl font-bold mt-1">
                {{ formatTokens(dayData?.summary?.totalTokens ?? 0) }}
              </div>
            </UCard>
            <UCard>
              <div class="text-sm text-gray-500 dark:text-gray-400">Input tokens</div>
              <div class="text-2xl font-bold mt-1">
                {{ formatTokens(dayData?.summary?.totalInputTokens ?? 0) }}
              </div>
            </UCard>
            <UCard>
              <div class="text-sm text-gray-500 dark:text-gray-400">Output tokens</div>
              <div class="text-2xl font-bold mt-1">
                {{ formatTokens(dayData?.summary?.totalOutputTokens ?? 0) }}
              </div>
            </UCard>
            <UCard>
              <div class="text-sm text-gray-500 dark:text-gray-400">Estimated cost</div>
              <div class="text-2xl font-bold mt-1">
                {{ formatCost(dayData?.summary?.totalEstimatedCost ?? 0) }}
              </div>
            </UCard>
          </div>

          <!-- Daily usage bar chart -->
          <UCard>
            <div class="h-80">
              <Bar
                v-if="(dayData?.buckets?.length ?? 0) > 0"
                :data="barChartData"
                :options="barChartOptions"
              />
              <div v-else class="flex items-center justify-center h-full text-gray-400">
                No usage data for this period
              </div>
            </div>
          </UCard>

          <!-- Breakdowns: Model & Provider -->
          <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <UCard>
              <div class="space-y-1 mb-4">
                <div class="text-lg font-semibold">By model</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">Token distribution across models</p>
              </div>
              <div class="h-64">
                <Doughnut
                  v-if="(modelData?.buckets?.length ?? 0) > 0"
                  :data="modelChartData"
                  :options="doughnutOptions"
                />
                <div v-else class="flex items-center justify-center h-full text-gray-400">
                  No model data
                </div>
              </div>
              <!-- Model table -->
              <div v-if="(modelData?.buckets?.length ?? 0) > 0" class="mt-4 space-y-2">
                <div
                  v-for="bucket in modelData!.buckets"
                  :key="bucket.key"
                  class="flex items-center justify-between text-sm"
                >
                  <span class="font-medium truncate">{{ bucket.key }}</span>
                  <span class="text-gray-500 dark:text-gray-400 tabular-nums">
                    {{ formatTokens(bucket.totalTokens) }} · {{ formatCost(bucket.estimatedCost) }}
                  </span>
                </div>
              </div>
            </UCard>

            <UCard>
              <div class="space-y-1 mb-4">
                <div class="text-lg font-semibold">By provider</div>
                <p class="text-sm text-gray-500 dark:text-gray-400">Token distribution across providers</p>
              </div>
              <div class="h-64">
                <Doughnut
                  v-if="(providerData?.buckets?.length ?? 0) > 0"
                  :data="providerChartData"
                  :options="doughnutOptions"
                />
                <div v-else class="flex items-center justify-center h-full text-gray-400">
                  No provider data
                </div>
              </div>
              <!-- Provider table -->
              <div v-if="(providerData?.buckets?.length ?? 0) > 0" class="mt-4 space-y-2">
                <div
                  v-for="bucket in providerData!.buckets"
                  :key="bucket.key"
                  class="flex items-center justify-between text-sm"
                >
                  <span class="font-medium truncate">{{ bucket.key }}</span>
                  <span class="text-gray-500 dark:text-gray-400 tabular-nums">
                    {{ formatTokens(bucket.totalTokens) }} · {{ formatCost(bucket.estimatedCost) }}
                  </span>
                </div>
              </div>
            </UCard>
          </div>
        </div>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
