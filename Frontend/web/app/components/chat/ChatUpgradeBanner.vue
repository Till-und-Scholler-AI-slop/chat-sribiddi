<script setup lang="ts">
const { t } = useI18n()

defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  dismiss: []
  upgrade: []
}>()
</script>

<template>
  <UAlert
    v-if="show"
    color="primary"
    variant="subtle"
    icon="i-heroicons-sparkles"
    :title="t('billing.upgradeBanner.title')"
    :description="t('billing.upgradeBanner.description')"
    :close="{ 'aria-label': t('billing.upgradeBanner.dismiss') }"
    :actions="[
      {
        label: t('billing.upgradeBanner.cta'),
        color: 'primary',
        variant: 'solid',
        onClick: () => emit('upgrade')
      }
    ]"
    @close="emit('dismiss')"
  />
</template>
