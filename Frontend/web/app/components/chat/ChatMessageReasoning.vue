<script setup lang="ts">
const { t } = useI18n()
interface Props {
  text: string
  expanded: boolean
}
defineProps<Props>()
defineEmits<{ toggle: [] }>()
</script>

<template>
  <div class="mb-3 rounded-xl border border-gray-200/60 dark:border-white/[0.06] bg-gray-100/50 dark:bg-white/[0.02] overflow-hidden">
    <button
      class="w-full flex items-center gap-2 px-3 py-2.5 text-xs text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300 transition-colors"
      @click="$emit('toggle')"
    >
      <UIcon name="i-heroicons-light-bulb" class="size-3.5 text-amber-500/70" />
      <span class="font-medium">{{ t('chat.thoughts') }}</span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3.5 ml-auto transition-transform duration-200"
      />
    </button>
    <div class="collapsible" :class="{ 'is-open': expanded }">
      <div class="collapsible-inner">
        <div class="px-3 pb-3 text-sm text-gray-500 dark:text-gray-400 whitespace-pre-wrap border-t border-gray-200/40 dark:border-white/[0.04]">
          {{ text }}
        </div>
      </div>
    </div>
  </div>
</template>
