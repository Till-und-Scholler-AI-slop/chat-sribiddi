<script setup lang="ts">
import type { ModelOption } from '~/composables/useChatApi'
import { isModelLocked } from '~/utils/model-utils'
import { optionPriceLabel, PROVIDER_LABELS } from '~/utils/format'

const { t } = useI18n()

defineProps<{
  option: ModelOption
  favorite?: boolean
}>()
</script>

<template>
  <div
    class="flex min-w-0 w-full items-center justify-between gap-2"
    :class="{ 'opacity-60': isModelLocked(option) }"
  >
    <span class="truncate">{{ option.label }}</span>
    <div class="flex items-center gap-1 flex-shrink-0">
      <span
        v-if="optionPriceLabel(option)"
        class="text-[10px] text-emerald-600 dark:text-emerald-400"
      >
        {{ optionPriceLabel(option) }}
      </span>
      <UIcon
        v-if="favorite"
        name="i-heroicons-star-solid"
        class="size-3 text-yellow-400"
      />
      <UTooltip v-if="isModelLocked(option)" :text="t('models.keyRequired')">
        <UIcon
          name="i-heroicons-lock-closed"
          class="size-3.5 text-gray-500 dark:text-gray-400"
          :aria-label="t('models.keyRequired')"
        />
      </UTooltip>
      <UBadge :label="PROVIDER_LABELS[option.provider]" variant="soft" color="neutral" />
    </div>
  </div>
</template>
