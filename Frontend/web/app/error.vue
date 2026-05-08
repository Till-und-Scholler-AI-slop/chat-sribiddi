<script setup lang="ts">
import type { NuxtError } from '#app'

const props = defineProps<{
  error: NuxtError
}>()

const { t } = useI18n()
const statusCode = computed(() => props.error.statusCode || 500)
const title = computed(() =>
  statusCode.value === 404 ? t('errors.pageNotFoundTitle') : t('errors.unexpectedTitle')
)
const description = computed(() =>
  statusCode.value === 404
    ? t('errors.pageNotFoundDescription')
    : t('errors.unexpectedDescription')
)

function goToChat() {
  clearError({ redirect: '/chat' })
}
</script>

<template>
  <UApp>
    <main class="min-h-dvh bg-chat-gradient flex items-center justify-center px-4 py-10">
      <section class="w-full max-w-md rounded-lg border border-gray-200 bg-white/95 p-6 shadow-sm dark:border-white/10 dark:bg-gray-950/95">
        <p class="text-sm font-medium text-primary">{{ statusCode }}</p>
        <h1 class="mt-2 text-2xl font-semibold text-gray-950 dark:text-white">
          {{ title }}
        </h1>
        <p class="mt-3 text-sm leading-6 text-gray-600 dark:text-gray-300">
          {{ description }}
        </p>
        <div class="mt-6 flex gap-3">
          <UButton color="primary" icon="i-heroicons-arrow-path" @click="reloadNuxtApp()">
            {{ t('common.reload') }}
          </UButton>
          <UButton color="neutral" variant="soft" icon="i-heroicons-chat-bubble-left-right" @click="goToChat">
            {{ t('errors.backToChat') }}
          </UButton>
        </div>
      </section>
    </main>
  </UApp>
</template>
