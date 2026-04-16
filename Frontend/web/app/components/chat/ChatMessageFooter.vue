<script setup lang="ts">
interface Usage {
  promptTokens?: number | null
  generationTokens?: number | null
  totalTokens?: number | null
}
interface Cost {
  totalCost: number
}
interface Props {
  usage?: Usage | null
  cost?: Cost | null
  timestamp?: string | null
  formatTokenCount: (n?: number | null) => string
  formatCost: (n: number) => string
}
defineProps<Props>()
</script>

<template>
  <div
    v-if="usage || timestamp"
    class="flex items-center gap-2.5 mt-2.5 text-[11px] text-gray-400 dark:text-gray-500 flex-wrap"
  >
    <span v-if="usage?.promptTokens != null" class="inline-flex items-center gap-1">
      <UIcon name="i-heroicons-arrow-up-tray" class="size-3" />
      {{ formatTokenCount(usage.promptTokens) }}
    </span>
    <span v-if="usage?.generationTokens != null" class="inline-flex items-center gap-1">
      <UIcon name="i-heroicons-arrow-down-tray" class="size-3" />
      {{ formatTokenCount(usage.generationTokens) }}
    </span>
    <span v-if="usage?.totalTokens != null" class="inline-flex items-center gap-1">
      {{ formatTokenCount(usage.totalTokens) }} Tokens
    </span>
    <span
      v-if="cost"
      class="inline-flex items-center gap-1 text-emerald-600/80 dark:text-emerald-400/60"
    >
      {{ formatCost(cost.totalCost) }}
    </span>
    <span v-if="timestamp" class="select-none">{{ timestamp }}</span>
  </div>
</template>
