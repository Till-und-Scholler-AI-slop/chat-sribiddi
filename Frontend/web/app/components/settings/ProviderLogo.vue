<script setup lang="ts">
import type { ProviderCatalogItem } from '~/composables/useKeyApi'

const props = withDefaults(defineProps<{
  provider: Pick<ProviderCatalogItem, 'id' | 'label'>
  size?: 'sm' | 'md' | 'lg'
}>(), {
  size: 'md'
})

const iconByProvider: Record<string, string> = {
  openai: 'i-simple-icons-openai',
  anthropic: 'i-simple-icons-anthropic',
  google: 'i-simple-icons-googlegemini',
  openrouter: 'i-simple-icons-openrouter',
  'brave-search': 'i-simple-icons-brave',
  'github-copilot': 'i-simple-icons-githubcopilot',
  qwen: 'i-simple-icons-qwen',
  minimax: 'i-simple-icons-minimax',
  ollama: 'i-simple-icons-ollama',
  'lm-studio': 'i-simple-icons-lmstudio',
  mistral: 'i-simple-icons-mistralai',
  deepseek: 'i-simple-icons-deepseek',
  perplexity: 'i-simple-icons-perplexity',
  vllm: 'i-simple-icons-vllm'
}

const toneByProvider: Record<string, string> = {
  openai: 'bg-gray-950 text-white dark:bg-gray-50 dark:text-gray-950',
  anthropic: 'bg-[#d97757] text-white',
  google: 'bg-white text-gray-800 ring-1 ring-gray-200 dark:bg-gray-900 dark:text-gray-50 dark:ring-gray-700',
  openrouter: 'bg-gray-950 text-white dark:bg-gray-50 dark:text-gray-950',
  'brave-search': 'bg-[#fb542b] text-white',
  'github-copilot': 'bg-[#24292f] text-white',
  qwen: 'bg-[#5f4bff] text-white',
  minimax: 'bg-[#111827] text-white',
  ollama: 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-950',
  'lm-studio': 'bg-[#0f766e] text-white',
  mistral: 'bg-[#fa520f] text-white',
  deepseek: 'bg-[#4f7cff] text-white',
  perplexity: 'bg-[#1fb8cd] text-gray-950',
  vllm: 'bg-[#6d28d9] text-white'
}

const normalizedId = computed(() => props.provider.id.toLowerCase())
const iconName = computed(() => iconByProvider[normalizedId.value])
const toneClass = computed(() => toneByProvider[normalizedId.value] ?? 'bg-[var(--accent-soft)] text-[var(--accent-ink)]')
const sizeClass = computed(() => {
  if (props.size === 'sm') return 'size-8 rounded-lg'
  if (props.size === 'lg') return 'size-12 rounded-xl'
  return 'size-10 rounded-xl'
})
const iconSizeClass = computed(() => props.size === 'sm' ? 'size-4' : props.size === 'lg' ? 'size-6' : 'size-5')
const fallbackLabel = computed(() => props.provider.label.trim().slice(0, 2).toUpperCase())
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center justify-center shadow-sm"
    :class="[sizeClass, toneClass]"
    :title="provider.label"
    aria-hidden="true"
  >
    <UIcon
      v-if="iconName"
      :name="iconName"
      :class="iconSizeClass"
    />
    <span v-else class="font-serif-display text-sm leading-none">
      {{ fallbackLabel }}
    </span>
  </span>
</template>
