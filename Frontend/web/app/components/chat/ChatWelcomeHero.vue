<script setup lang="ts">
const { t } = useI18n()
interface Suggestion {
  label: string
  prompt: string
  aria: string
}
defineProps<{
  suggestions: Suggestion[]
}>()
const emit = defineEmits<{
  pick: [prompt: string]
  start: []
}>()
</script>

<template>
  <div class="welcome-hero animate-fade-in-up">
    <h2>{{ t('common.appName') }}</h2>
    <p class="mt-3 text-sm text-gray-500 dark:text-gray-400 max-w-md mx-auto">
      {{ t('chat.welcomeIntro') }}
    </p>
    <div class="mt-6 flex flex-wrap justify-center gap-3">
      <button
        v-for="suggestion in suggestions"
        :key="suggestion.prompt"
        class="prompt-suggestion"
        :aria-label="suggestion.aria"
        @click="emit('pick', suggestion.prompt)"
      >
        {{ suggestion.label }}
      </button>
    </div>
    <UButton color="primary" icon="i-heroicons-plus" class="mt-6 btn-interactive" @click="emit('start')">
      {{ t('chat.startChat') }}
    </UButton>
  </div>
</template>
