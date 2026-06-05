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
        <h1 class="text-2xl font-semibold">{{ t('legal.datenschutz.title') }}</h1>
        <p class="text-sm text-gray-600 dark:text-gray-400">
          {{ t('legal.datenschutz.subtitle') }}
        </p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>{{ t('legal.datenschutz.intro') }}</p>

        <p>
          <strong>{{ t('legal.datenschutz.controllerHeading') }}</strong><br>
          {{ t('legal.impressum.address.name') }}<br>
          {{ t('legal.impressum.address.street') }}<br>
          {{ t('legal.impressum.address.city') }}<br>
          {{ t('legal.impressum.address.country') }}<br>
          {{ t('legal.impressum.email') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.dataHeading') }}</strong><br>
          {{ t('legal.datenschutz.dataBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.chatHeading') }}</strong><br>
          {{ t('legal.datenschutz.chatBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.purposeHeading') }}</strong><br>
          {{ t('legal.datenschutz.purposeBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.recipientsHeading') }}</strong><br>
          {{ t('legal.datenschutz.recipientsBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.retentionHeading') }}</strong><br>
          {{ t('legal.datenschutz.retentionBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.rightsHeading') }}</strong><br>
          {{ t('legal.datenschutz.rightsBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.providerKeysHeading') }}</strong><br>
          {{ t('legal.datenschutz.providerKeysBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.adSenseHeading') }}</strong><br>
          {{ t('legal.datenschutz.adSenseBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.aiProvidersHeading') }}</strong><br>
          {{ t('legal.datenschutz.aiProvidersBody') }}
        </p>

        <p>
          <strong>{{ t('legal.datenschutz.dpaHeading') }}</strong><br>
          {{ t('legal.datenschutz.dpaBody') }}
        </p>
      </div>

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" :label="t('legal.backToLogin')" />
        <UButton to="/impressum" variant="ghost" color="neutral" :label="t('legal.impressum.title')" />
        <UButton to="/agb" variant="ghost" color="neutral" :label="t('legal.agb.title')" />
      </div>
    </div>
  </UCard>
</template>
