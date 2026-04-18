<script setup lang="ts">
interface Props {
  text: string
  expanded: boolean
}
defineProps<Props>()
defineEmits<{ toggle: [] }>()
</script>

<template>
  <div class="reasoning-block">
    <button
      type="button"
      class="reasoning-trigger"
      @click="$emit('toggle')"
    >
      <UIcon name="i-heroicons-light-bulb" class="size-3.5 reasoning-icon" />
      <span class="font-medium">Gedankengang</span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3.5 ml-auto transition-transform duration-200"
      />
    </button>
    <div class="collapsible" :class="{ 'is-open': expanded }">
      <div class="collapsible-inner">
        <div class="reasoning-body whitespace-pre-wrap">
          {{ text }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reasoning-block {
  margin-bottom: 0.75rem;
  border-radius: 0.875rem;
  border: 1px solid var(--hair);
  background: var(--paper-2);
  overflow: hidden;
}
.reasoning-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem 0.5rem 0.875rem;
  font-size: 0.75rem;
  color: var(--ink-2);
  transition: color 150ms ease;
}
.reasoning-trigger:hover {
  color: var(--ink);
}
.reasoning-icon {
  color: var(--accent);
}
.reasoning-body {
  padding: 0.625rem 0.875rem 0.75rem;
  font-size: 0.8125rem;
  color: var(--ink-2);
  border-top: 1px solid var(--hair-2);
}
</style>
