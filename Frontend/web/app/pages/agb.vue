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
        <h1 class="text-2xl font-semibold">{{ t('legal.agb.title') }}</h1>
        <p class="text-sm text-gray-600 dark:text-gray-400">{{ t('legal.agb.subtitle') }}</p>
        <p class="text-xs text-amber-600 dark:text-amber-400">{{ t('legal.agb.placeholderNotice') }}</p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>{{ t('legal.agb.intro') }}</p>

        <p>
          <strong>{{ t('legal.agb.scopeHeading') }}</strong><br>
          {{ t('legal.agb.scopeBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.servicesHeading') }}</strong><br>
          {{ t('legal.agb.servicesBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.pricesHeading') }}</strong><br>
          {{ t('legal.agb.pricesBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.termHeading') }}</strong><br>
          {{ t('legal.agb.termBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.withdrawalHeading') }}</strong><br>
          {{ t('legal.agb.withdrawalBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.liabilityHeading') }}</strong><br>
          {{ t('legal.agb.liabilityBody') }}
        </p>

        <p>
          <strong>{{ t('legal.agb.miscHeading') }}</strong><br>
          {{ t('legal.agb.miscBody') }}
        </p>
      </div>

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" :label="t('legal.backToLogin')" />
        <UButton to="/impressum" variant="ghost" color="neutral" :label="t('legal.impressum.title')" />
        <UButton to="/datenschutz" variant="ghost" color="neutral" :label="t('legal.datenschutz.title')" />
      </div>
    </div>
  </UCard>
</template>
