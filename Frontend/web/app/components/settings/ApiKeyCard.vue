<script setup lang="ts">
const { t } = useI18n()

const props = defineProps<{
  provider: string
  titleKey: string
  labelKey: string
  placeholder: string
  descriptionKey: string
  statusActive: boolean
  modelValue: string
  reveal: boolean
  saving: boolean
  deleting: boolean
}>()

const emit = defineEmits<{
  save: []
  delete: []
  'update:modelValue': [value: string]
  'update:reveal': [value: boolean]
}>()

const keyPresent = computed(() => props.modelValue.trim().length > 0)
const isBusy = computed(() => props.saving || props.deleting)
</script>

<template>
  <UCard class="flex flex-col card-elevated">
    <div class="flex flex-col gap-5 h-full">
      <div class="flex items-center justify-between gap-3 flex-wrap">
        <div class="flex items-center gap-2">
          <span class="status-dot" :class="statusActive ? 'status-dot-active' : 'status-dot-inactive'" />
          <div class="text-base font-semibold">{{ t(titleKey) }}</div>
        </div>
        <UBadge
          variant="soft"
          :color="statusActive ? 'primary' : 'neutral'"
          :label="statusActive ? t('settings.stored') : t('settings.notStored')"
        />
      </div>

      <UFormField
        :label="t(labelKey)"
        :description="t(descriptionKey)"
        :name="provider.toLowerCase()"
      >
        <UInput
          :model-value="modelValue"
          :type="reveal ? 'text' : 'password'"
          :placeholder="placeholder"
          icon="i-heroicons-lock-closed"
          autocomplete="off"
          class="w-full"
          @update:model-value="emit('update:modelValue', $event)"
        />
      </UFormField>

      <div class="mt-auto pt-4 border-t border-gray-200 dark:border-gray-800">
        <div class="flex items-center justify-between gap-3 flex-wrap">
          <div class="flex items-center gap-3 flex-wrap">
            <UButton
              type="button"
              variant="ghost"
              color="neutral"
              size="sm"
              :icon="reveal ? 'i-heroicons-eye-slash' : 'i-heroicons-eye'"
              :label="reveal ? t('common.hide') : t('common.show')"
              @click="emit('update:reveal', !reveal)"
            />
            <UButton
              type="button"
              color="primary"
              size="sm"
              icon="i-heroicons-check-circle"
              :label="t('settings.saveKey')"
              class="btn-interactive"
              :loading="saving"
              :disabled="!keyPresent || isBusy"
              @click="emit('save')"
            />
          </div>
          <UButton
            type="button"
            variant="outline"
            color="error"
            size="sm"
            icon="i-heroicons-trash"
            :label="t('common.remove')"
            :loading="deleting"
            :disabled="isBusy"
            @click="emit('delete')"
          />
        </div>
      </div>
    </div>
  </UCard>
</template>
