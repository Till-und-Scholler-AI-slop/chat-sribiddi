<script setup lang="ts">
import type { ToolCallLog } from '~/composables/useChatApi'
import { filterToolCallLogs } from '~/composables/useChatStore'

const props = defineProps<{
  toolCalls: ToolCallLog[]
  loading: boolean
  error: string | null
}>()

const expanded = ref(false)
const filterQuery = ref('')

const filteredToolCalls = computed(() => filterToolCallLogs(props.toolCalls, filterQuery.value))

const formattedToolCallsJson = computed(() => JSON.stringify(filteredToolCalls.value, null, 2))

const toolTriggerLabel = computed(() => {
  if (props.loading) return 'Using tools'
  const count = props.toolCalls.length
  if (count === 0) return 'Tool activity'
  if (count === 1) return formatToolNameForDisplay(props.toolCalls[0].toolName)
  return `${count} tool calls`
})

function toggle() {
  expanded.value = !expanded.value
}

function formatToolNameForDisplay(toolName: string) {
  const raw = toolName.split('.').pop() ?? toolName
  return raw
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .replace(/^\w/, character => character.toUpperCase())
}

function formatTimestamp(value?: string | null) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

function formatPayload(payload?: string | null) {
  if (!payload) return '—'

  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch (error) {
    if (import.meta.dev) {
      console.debug('Failed to parse tool payload as JSON', error)
    }
    return payload
  }
}
</script>

<template>
  <div class="tool-call-display">
    <button
      class="tool-call-trigger"
      type="button"
      :aria-expanded="expanded"
      @click="toggle"
    >
      <span class="inline-flex items-center gap-2 min-w-0">
        <UIcon
          :name="loading ? 'i-heroicons-arrow-path' : 'i-heroicons-wrench-screwdriver'"
          class="size-3.5 shrink-0 text-gray-400 dark:text-gray-500"
          :class="{ 'animate-spin': loading }"
        />
        <span class="truncate">{{ toolTriggerLabel }}</span>
      </span>
      <span v-if="!loading && toolCalls.length > 1" class="tool-call-count">
        {{ toolCalls.length }}
      </span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3.5 shrink-0 text-gray-400 dark:text-gray-500"
      />
    </button>

    <div class="collapsible-content" :class="{ 'is-expanded': expanded }">
      <div class="collapsible-inner">
        <div class="tool-call-panel">
          <div v-if="error" class="tool-call-alert text-red-400 dark:text-red-300">
            <UIcon name="i-heroicons-exclamation-circle" class="size-4 shrink-0" />
            <span>{{ error }}</span>
          </div>

          <div v-else-if="loading && toolCalls.length === 0" class="tool-call-alert">
            <UIcon name="i-heroicons-arrow-path" class="size-4 shrink-0 animate-spin" />
            <span>Waiting for tool execution details…</span>
          </div>

          <template v-else>
            <div class="panel-toolbar">
              <label class="search-field">
                <UIcon name="i-heroicons-magnifying-glass" class="size-3.5 text-gray-400 dark:text-gray-500" />
                <input
                  v-model="filterQuery"
                  type="search"
                  placeholder="Filter by tool name"
                  class="search-input"
                >
              </label>
            </div>

            <div v-if="filteredToolCalls.length === 0" class="tool-call-empty">
              No tool calls match “{{ filterQuery.trim() }}”.
            </div>

            <div v-else class="tool-call-list">
              <details
                v-for="tc in filteredToolCalls"
                :key="tc.id ?? `${tc.toolName}-${tc.startedAt}`"
                class="tool-call-entry"
                :open="filteredToolCalls.length === 1"
              >
                <summary class="tool-call-entry-summary">
                  <span class="min-w-0">
                    <span class="block truncate text-sm text-gray-700 dark:text-gray-200">
                      {{ formatToolNameForDisplay(tc.toolName) }}
                    </span>
                    <span class="block truncate text-[11px] text-gray-400 dark:text-gray-500">
                      {{ tc.toolName }}
                    </span>
                  </span>
                  <span class="tool-call-meta">
                    <span v-if="formatTimestamp(tc.startedAt)">{{ formatTimestamp(tc.startedAt) }}</span>
                    <span v-if="tc.durationMs != null">{{ tc.durationMs }}ms</span>
                  </span>
                </summary>

                <div class="tool-call-entry-body">
                  <div class="payload-block">
                    <div class="payload-label">Input</div>
                    <pre>{{ formatPayload(tc.inputPayload) }}</pre>
                  </div>

                  <div class="payload-block">
                    <div class="payload-label">Output</div>
                    <pre>{{ formatPayload(tc.outputPayload) }}</pre>
                  </div>

                  <div v-if="tc.contextExcerpt" class="payload-block">
                    <div class="payload-label">Context excerpt</div>
                    <pre>{{ formatPayload(tc.contextExcerpt) }}</pre>
                  </div>
                </div>
              </details>
            </div>

            <div class="payload-block raw-json-block">
              <div class="payload-label">Execution log JSON</div>
              <pre>{{ formattedToolCallsJson }}</pre>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-display {
  margin-bottom: 0.5rem;
}

.tool-call-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  justify-content: space-between;
  padding: 0.375rem 0.75rem;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, currentColor 8%, transparent);
  background: color-mix(in srgb, currentColor 3%, transparent);
  font-size: 0.75rem;
  line-height: 1rem;
  color: rgb(107 114 128);
  transition: background-color 0.2s ease, border-color 0.2s ease;
}

.tool-call-trigger:hover {
  background: color-mix(in srgb, currentColor 5%, transparent);
}

.dark .tool-call-trigger {
  color: rgb(156 163 175);
}

.tool-call-count {
  padding: 0.125rem 0.375rem;
  border-radius: 999px;
  background: color-mix(in srgb, currentColor 8%, transparent);
  font-size: 0.6875rem;
}

.collapsible-content {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.25s ease;
}

.collapsible-content.is-expanded {
  grid-template-rows: 1fr;
}

.collapsible-inner {
  overflow: hidden;
}

.tool-call-panel {
  margin-top: 0.5rem;
  border-radius: 1rem;
  border: 1px solid color-mix(in srgb, currentColor 8%, transparent);
  background: color-mix(in srgb, currentColor 2%, transparent);
  padding: 0.75rem;
}

.tool-call-alert,
.tool-call-empty {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
  color: rgb(107 114 128);
}

.dark .tool-call-alert,
.dark .tool-call-empty {
  color: rgb(156 163 175);
}

.panel-toolbar {
  margin-bottom: 0.75rem;
}

.search-field {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid color-mix(in srgb, currentColor 8%, transparent);
  background: color-mix(in srgb, currentColor 3%, transparent);
  padding: 0.5rem 0.625rem;
  color: rgb(107 114 128);
}

.dark .search-field {
  color: rgb(156 163 175);
}

.search-input {
  width: 100%;
  background: transparent;
  border: 0;
  outline: 0;
  font-size: 0.8125rem;
  color: inherit;
}

.tool-call-list {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

.tool-call-entry {
  border-radius: 0.875rem;
  border: 1px solid color-mix(in srgb, currentColor 8%, transparent);
  background: color-mix(in srgb, currentColor 2%, transparent);
}

.tool-call-entry-summary {
  list-style: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.75rem;
  cursor: pointer;
}

.tool-call-entry-summary::-webkit-details-marker {
  display: none;
}

.tool-call-meta {
  display: inline-flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: flex-end;
  font-size: 0.6875rem;
  color: rgb(156 163 175);
}

.tool-call-entry-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0 0.75rem 0.75rem;
}

.payload-block {
  border-radius: 0.75rem;
  border: 1px solid color-mix(in srgb, currentColor 8%, transparent);
  background: color-mix(in srgb, currentColor 3%, transparent);
  padding: 0.625rem;
}

.payload-label {
  margin-bottom: 0.375rem;
  font-size: 0.6875rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: rgb(156 163 175);
}

.payload-block pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.75rem;
  line-height: 1.2rem;
  color: rgb(55 65 81);
}

.dark .payload-block pre {
  color: rgb(224 225 221);
}

.raw-json-block {
  margin-top: 0.75rem;
}
</style>
