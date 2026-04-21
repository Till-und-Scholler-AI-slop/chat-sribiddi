<script setup lang="ts">
const { t } = useI18n()
definePageMeta({
  layout: 'auth'
})
</script>

<template>
  <UCard class="mx-auto max-w-2xl">
    <div class="space-y-6">
      <div class="space-y-1">
        <h1 class="text-2xl font-semibold">{{ t('legal.impressum.title') }}</h1>
        <p class="text-sm text-gray-600 dark:text-gray-400">{{ t('legal.impressum.subtitle') }}</p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>
          <strong>{{ t('legal.impressum.siteName') }}</strong><br>
          {{ t('legal.impressum.address.name') }}<br>
          {{ t('legal.impressum.address.street') }}<br>
          {{ t('legal.impressum.address.city') }}<br>
          {{ t('legal.impressum.address.country') }}
        </p>

        <p>
          {{ t('legal.impressum.contactHeading') }}<br>
          {{ t('legal.impressum.email') }}
        </p>

        <p>
          {{ t('legal.impressum.responsibleHeading') }}<br>
          {{ t('legal.impressum.responsibleAddress') }}
        </p>

        <p>
          <strong>{{ t('legal.impressum.vatIdHeading') }}</strong><br>
          {{ t('legal.impressum.vatId') }}
        </p>

        <p>
          <strong>{{ t('legal.impressum.registerCourtHeading') }}</strong><br>
          {{ t('legal.impressum.registerCourt') }}
        </p>
      </div>

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" :label="t('legal.backToLogin')" />
        <UButton to="/datenschutz" variant="ghost" color="neutral" :label="t('legal.datenschutz.title')" />
        <UButton to="/agb" variant="ghost" color="neutral" :label="t('legal.agb.title')" />
      </div>
    </div>
  </UCard>
</template>
