<script setup lang="ts">
import type { AvailableTool } from '~/composables/useToolApi'

const props = defineProps<{
  chatId: string
  tools: AvailableTool[]
  loading: boolean
}>()

const toolIcons: Record<string, string> = {
  calculator: 'i-heroicons-calculator',
  'url-fetcher': 'i-heroicons-globe-alt',
  'brave-search': 'i-heroicons-magnifying-glass',
  'current-datetime': 'i-heroicons-clock'
}

const getIcon = (name: string) => toolIcons[name] || 'i-heroicons-wrench'
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center gap-2 px-1">
      <UIcon name="i-heroicons-wrench-screwdriver" class="size-4 text-gray-500 dark:text-gray-400" />
      <span class="text-xs font-medium text-gray-600 dark:text-gray-300">Tools</span>
    </div>
    <div v-if="loading" class="flex items-center gap-2 px-3 py-2 text-xs text-gray-400">
      <UIcon name="i-heroicons-arrow-path" class="size-3 animate-spin" />
      Loading tools...
    </div>
    <div v-else-if="tools.length === 0" class="px-3 py-2 text-xs text-gray-400">
      No tools available
    </div>
    <div v-else class="flex flex-wrap gap-1.5">
      <UTooltip v-for="tool in tools" :key="tool.name" :text="tool.description">
        <span class="tool-chip tool-chip-active">
          <UIcon :name="getIcon(tool.name)" class="size-3.5" />
          <span class="text-xs">{{ tool.displayName }}</span>
        </span>
      </UTooltip>
    </div>
  </div>
</template>

<style scoped>
.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.625rem;
  border-radius: 9999px;
  font-size: 0.75rem;
  border: 1px solid rgb(229 231 235 / 0.6);
  color: rgb(107 114 128);
  background: rgb(249 250 251 / 0.5);
}
.dark .tool-chip {
  border-color: rgb(255 255 255 / 0.08);
  color: rgb(156 163 175);
  background: rgb(255 255 255 / 0.03);
}
.tool-chip-active {
  border-color: rgb(99 102 241 / 0.5);
  background: rgb(99 102 241 / 0.1);
  color: rgb(79 70 229);
}
.dark .tool-chip-active {
  border-color: rgb(129 140 248 / 0.4);
  background: rgb(129 140 248 / 0.1);
  color: rgb(165 180 252);
}
</style>
