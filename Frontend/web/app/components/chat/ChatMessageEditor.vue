<script setup lang="ts">
const modelValue = defineModel<string>({ required: true })
defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{
  submit: []
  cancel: []
}>()
</script>

<template>
  <div class="space-y-2">
    <textarea
      v-model="modelValue"
      class="w-full min-h-[80px] rounded-xl bg-gray-100/80 dark:bg-white/[0.04] border border-gray-200 dark:border-white/[0.08] text-sm text-gray-800 dark:text-gray-100 px-3.5 py-2.5 resize-y focus:outline-none focus:ring-2 focus:ring-primary/40 transition-shadow"
      @keydown.enter.ctrl="emit('submit')"
      @keydown.escape="emit('cancel')"
    />
    <div class="flex items-center gap-2">
      <UButton
        size="xs"
        color="primary"
        icon="i-heroicons-check"
        label="Speichern & neu generieren"
        class="btn-interactive"
        :disabled="disabled || !modelValue.trim()"
        @click="emit('submit')"
      />
      <UButton
        size="xs"
        color="neutral"
        variant="ghost"
        icon="i-heroicons-x-mark"
        label="Abbrechen"
        @click="emit('cancel')"
      />
    </div>
  </div>
</template>
