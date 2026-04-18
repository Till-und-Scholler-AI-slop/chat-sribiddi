<script setup lang="ts">
import type { ToolCallLog } from '~/composables/useChatApi'
import { filterToolCallLogs } from '~/composables/chat-helpers'

const { t } = useI18n()

const props = defineProps<{
  toolCalls: ToolCallLog[]
  loading: boolean
  error: string | null
}>()

const expanded = ref(false)
const filterQuery = ref('')
const showRawJson = ref(false)

const filteredToolCalls = computed(() => filterToolCallLogs(props.toolCalls, filterQuery.value))

const formattedToolCallsJson = computed(() => JSON.stringify(props.toolCalls, null, 2))

const toolTriggerLabel = computed(() => {
  if (props.loading) return t('tools.running')
  const count = props.toolCalls.length
  if (count === 0) return t('tools.activity')
  if (count === 1) return formatToolNameForDisplay(props.toolCalls[0].toolName)
  return t('tools.callsCount', { count })
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

function statusIcon(tc: ToolCallLog) {
  if (tc.outputPayload) return 'i-heroicons-check-circle'
  if (tc.durationMs != null) return 'i-heroicons-check-circle'
  return 'i-heroicons-arrow-path'
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
      <span class="trigger-icon">
        <UIcon
          :name="loading ? 'i-heroicons-arrow-path' : 'i-heroicons-wrench-screwdriver'"
          class="size-3.5"
          :class="{ 'animate-spin': loading }"
        />
      </span>
      <span class="trigger-label">{{ toolTriggerLabel }}</span>
      <span v-if="!loading && toolCalls.length > 1" class="tool-call-count">
        {{ toolCalls.length }}
      </span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3.5 trigger-chevron"
      />
    </button>

    <div class="collapsible-content" :class="{ 'is-expanded': expanded }">
      <div class="collapsible-inner">
        <div class="tool-call-panel">
          <div v-if="error" class="tool-call-alert is-error">
            <UIcon name="i-heroicons-exclamation-circle" class="size-4 shrink-0" />
            <span>{{ error }}</span>
          </div>

          <div v-else-if="loading && toolCalls.length === 0" class="tool-call-alert">
            <UIcon name="i-heroicons-arrow-path" class="size-4 shrink-0 animate-spin" />
            <span>{{ t('tools.waitingResults') }}</span>
          </div>

          <template v-else>
            <div v-if="toolCalls.length > 1" class="panel-toolbar">
              <label class="search-field">
                <UIcon name="i-heroicons-magnifying-glass" class="size-3.5" />
                <input
                  v-model="filterQuery"
                  type="search"
                  :placeholder="t('tools.filterByName')"
                  class="search-input"
                >
              </label>
            </div>

            <div v-if="filteredToolCalls.length === 0" class="tool-call-empty">
              {{ t('tools.noToolMatches', { query: filterQuery.trim() }) }}
            </div>

            <div v-else class="tool-call-list">
              <details
                v-for="(tc, index) in filteredToolCalls"
                :key="tc.id ?? `${tc.messageId}-${tc.toolName}-${tc.startedAt ?? 'unknown'}-${index}`"
                class="tool-call-entry"
                :open="filteredToolCalls.length === 1"
              >
                <summary class="tool-call-entry-summary">
                  <span class="entry-status">
                    <UIcon :name="statusIcon(tc)" class="size-4" />
                  </span>
                  <span class="entry-titles">
                    <span class="entry-title">
                      {{ formatToolNameForDisplay(tc.toolName) }}
                    </span>
                    <span class="entry-subtitle">
                      {{ tc.toolName }}
                    </span>
                  </span>
                  <span class="tool-call-meta">
                    <span v-if="tc.durationMs != null" class="meta-pill">
                      <UIcon name="i-heroicons-clock" class="size-3" />
                      {{ tc.durationMs }}ms
                    </span>
                    <span v-if="formatTimestamp(tc.startedAt)" class="meta-time">
                      {{ formatTimestamp(tc.startedAt) }}
                    </span>
                  </span>
                  <UIcon
                    name="i-heroicons-chevron-down"
                    class="size-3.5 entry-chevron"
                  />
                </summary>

                <div class="tool-call-entry-body">
                  <div class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-arrow-up-tray" class="size-3" />
                      {{ t('tools.input') }}
                    </div>
                    <pre>{{ formatPayload(tc.inputPayload) }}</pre>
                  </div>

                  <div class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-arrow-down-tray" class="size-3" />
                      {{ t('tools.output') }}
                    </div>
                    <pre>{{ formatPayload(tc.outputPayload) }}</pre>
                  </div>

                  <div v-if="tc.contextExcerpt" class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-document-text" class="size-3" />
                      {{ t('tools.contextExcerpt') }}
                    </div>
                    <pre>{{ formatPayload(tc.contextExcerpt) }}</pre>
                  </div>
                </div>
              </details>
            </div>

            <div class="raw-json-toggle">
              <button type="button" class="raw-json-button" @click="showRawJson = !showRawJson">
                <UIcon :name="showRawJson ? 'i-heroicons-eye-slash' : 'i-heroicons-code-bracket'" class="size-3" />
                {{ showRawJson ? t('tools.hideRawJson') : t('tools.showRawJson') }}
              </button>
              <div v-if="showRawJson" class="payload-block raw-json-block">
                <pre>{{ formattedToolCallsJson }}</pre>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-display {
  margin-bottom: 0.75rem;
}

/* ── Trigger pill ──────────────────────────────────────────────────── */
.tool-call-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.875rem;
  border-radius: 0.75rem;
  border: 1px solid rgba(184, 46, 142, 0.18);
  background: rgba(184, 46, 142, 0.06);
  font-size: 0.8125rem;
  line-height: 1.1rem;
  color: rgb(75 85 99);
  text-align: left;
  transition: all 160ms ease;
}

.tool-call-trigger:hover {
  background: rgba(184, 46, 142, 0.1);
  border-color: rgba(184, 46, 142, 0.3);
}

.dark .tool-call-trigger {
  background: rgba(229, 51, 138, 0.08);
  border-color: rgba(229, 51, 138, 0.22);
  color: rgb(203 213 225);
}

.dark .tool-call-trigger:hover {
  background: rgba(229, 51, 138, 0.14);
  border-color: rgba(229, 51, 138, 0.36);
}

.trigger-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.5rem;
  background: rgba(184, 46, 142, 0.14);
  color: #B82E8E;
  flex-shrink: 0;
}

.dark .trigger-icon {
  background: rgba(229, 51, 138, 0.18);
  color: #E5338A;
}

.trigger-label {
  flex: 1;
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trigger-chevron {
  opacity: 0.6;
  transition: transform 200ms ease;
}

.tool-call-trigger[aria-expanded="true"] .trigger-chevron {
  transform: rotate(0deg);
}

.tool-call-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.25rem;
  height: 1.25rem;
  padding: 0 0.375rem;
  border-radius: 999px;
  background: rgba(184, 46, 142, 0.18);
  color: #B82E8E;
  font-size: 0.6875rem;
  font-weight: 600;
}

.dark .tool-call-count {
  background: rgba(229, 51, 138, 0.22);
  color: #F472B6;
}

/* ── Collapsible ───────────────────────────────────────────────────── */
.collapsible-content {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 220ms ease;
}

.collapsible-content.is-expanded {
  grid-template-rows: 1fr;
}

.collapsible-inner {
  overflow: hidden;
}

/* ── Panel ─────────────────────────────────────────────────────────── */
.tool-call-panel {
  margin-top: 0.5rem;
  border-radius: 0.875rem;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.6);
  padding: 0.875rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.dark .tool-call-panel {
  background: rgba(20, 10, 33, 0.4);
  border-color: rgba(255, 255, 255, 0.06);
}

.tool-call-alert,
.tool-call-empty {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
  color: rgb(107 114 128);
}

.tool-call-alert.is-error {
  color: rgb(220 38 38);
}

.dark .tool-call-alert,
.dark .tool-call-empty {
  color: rgb(156 163 175);
}

.dark .tool-call-alert.is-error {
  color: rgb(252 165 165);
}

/* ── Search ───────────────────────────────────────────────────────── */
.search-field {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  border-radius: 0.625rem;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.6);
  padding: 0.4rem 0.625rem;
  color: rgb(107 114 128);
}

.dark .search-field {
  background: rgba(0, 0, 0, 0.2);
  border-color: rgba(255, 255, 255, 0.08);
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

/* ── Entry list ───────────────────────────────────────────────────── */
.tool-call-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.tool-call-entry {
  border-radius: 0.75rem;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.5);
  overflow: hidden;
  transition: border-color 150ms ease;
}

.tool-call-entry[open] {
  border-color: rgba(184, 46, 142, 0.25);
}

.dark .tool-call-entry {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
}

.dark .tool-call-entry[open] {
  border-color: rgba(229, 51, 138, 0.35);
}

.tool-call-entry-summary {
  list-style: none;
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.625rem 0.75rem;
  cursor: pointer;
  user-select: none;
}

.tool-call-entry-summary::-webkit-details-marker {
  display: none;
}

.entry-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.5rem;
  background: rgba(34, 197, 94, 0.12);
  color: rgb(22 163 74);
  flex-shrink: 0;
}

.dark .entry-status {
  background: rgba(34, 197, 94, 0.18);
  color: rgb(74 222 128);
}

.entry-titles {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.entry-title {
  font-size: 0.8125rem;
  font-weight: 500;
  color: rgb(31 41 55);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dark .entry-title {
  color: rgb(229 231 235);
}

.entry-subtitle {
  font-size: 0.6875rem;
  color: rgb(156 163 175);
  font-family: var(--font-mono, monospace);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-call-meta {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
}

.meta-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.125rem 0.4rem;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.05);
  color: rgb(107 114 128);
  font-size: 0.6875rem;
  font-variant-numeric: tabular-nums;
}

.dark .meta-pill {
  background: rgba(255, 255, 255, 0.06);
  color: rgb(156 163 175);
}

.meta-time {
  font-size: 0.6875rem;
  color: rgb(156 163 175);
  font-variant-numeric: tabular-nums;
}

.entry-chevron {
  color: rgb(156 163 175);
  flex-shrink: 0;
  transition: transform 200ms ease;
}

.tool-call-entry[open] .entry-chevron {
  transform: rotate(180deg);
}

/* ── Entry body ───────────────────────────────────────────────────── */
.tool-call-entry-body {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0 0.75rem 0.75rem;
}

.payload-block {
  border-radius: 0.625rem;
  background: rgba(0, 0, 0, 0.03);
  padding: 0.5rem 0.625rem;
}

.dark .payload-block {
  background: rgba(0, 0, 0, 0.25);
}

.payload-label {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  margin-bottom: 0.375rem;
  font-size: 0.6875rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: rgb(107 114 128);
}

.dark .payload-label {
  color: rgb(156 163 175);
}

.payload-block pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.75rem;
  line-height: 1.25rem;
  color: rgb(55 65 81);
  font-family: var(--font-mono, monospace);
  max-height: 18rem;
  overflow: auto;
}

.dark .payload-block pre {
  color: rgb(224 225 221);
}

/* ── Raw JSON toggle ──────────────────────────────────────────────── */
.raw-json-toggle {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.raw-json-button {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  background: transparent;
  color: rgb(107 114 128);
  font-size: 0.6875rem;
  font-weight: 500;
  transition: background-color 150ms ease;
}

.raw-json-button:hover {
  background: rgba(0, 0, 0, 0.05);
  color: rgb(55 65 81);
}

.dark .raw-json-button {
  color: rgb(156 163 175);
}

.dark .raw-json-button:hover {
  background: rgba(255, 255, 255, 0.06);
  color: rgb(229 231 235);
}

.raw-json-block pre {
  max-height: 20rem;
}
</style>
