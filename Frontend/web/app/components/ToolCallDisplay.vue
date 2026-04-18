<script setup lang="ts">
import type { ToolCallLog } from '~/composables/useChatApi'
import { filterToolCallLogs } from '~/composables/chat-helpers'

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
  if (props.loading) return 'Tools werden ausgeführt'
  const count = props.toolCalls.length
  if (count === 0) return 'Tool-Aktivität'
  if (count === 1) return formatToolNameForDisplay(props.toolCalls[0].toolName)
  return `${count} Tool-Aufrufe`
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
            <span>Warte auf Ergebnisse…</span>
          </div>

          <template v-else>
            <div v-if="toolCalls.length > 1" class="panel-toolbar">
              <label class="search-field">
                <UIcon name="i-heroicons-magnifying-glass" class="size-3.5" />
                <input
                  v-model="filterQuery"
                  type="search"
                  placeholder="Nach Tool-Namen filtern"
                  class="search-input"
                >
              </label>
            </div>

            <div v-if="filteredToolCalls.length === 0" class="tool-call-empty">
              Kein Tool entspricht „{{ filterQuery.trim() }}“.
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
                      Input
                    </div>
                    <pre>{{ formatPayload(tc.inputPayload) }}</pre>
                  </div>

                  <div class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-arrow-down-tray" class="size-3" />
                      Output
                    </div>
                    <pre>{{ formatPayload(tc.outputPayload) }}</pre>
                  </div>

                  <div v-if="tc.contextExcerpt" class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-document-text" class="size-3" />
                      Kontextauszug
                    </div>
                    <pre>{{ formatPayload(tc.contextExcerpt) }}</pre>
                  </div>
                </div>
              </details>
            </div>

            <div class="raw-json-toggle">
              <button type="button" class="raw-json-button" @click="showRawJson = !showRawJson">
                <UIcon :name="showRawJson ? 'i-heroicons-eye-slash' : 'i-heroicons-code-bracket'" class="size-3" />
                {{ showRawJson ? 'Roh-JSON ausblenden' : 'Roh-JSON anzeigen' }}
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
  border: 1px solid var(--accent-line);
  background: var(--accent-soft);
  font-size: 0.8125rem;
  line-height: 1.1rem;
  color: var(--accent-ink);
  text-align: left;
  transition: all 160ms ease;
}

.tool-call-trigger:hover {
  background: color-mix(in oklab, var(--accent) 14%, var(--accent-soft));
  border-color: var(--accent);
}

.trigger-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.5rem;
  background: color-mix(in oklab, var(--accent) 22%, transparent);
  color: var(--accent-ink);
  flex-shrink: 0;
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
  background: color-mix(in oklab, var(--accent) 25%, transparent);
  color: var(--accent-ink);
  font-size: 0.6875rem;
  font-weight: 600;
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
  border: 1px solid var(--hair);
  background: var(--paper-2);
  padding: 0.875rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.tool-call-alert,
.tool-call-empty {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
  color: var(--ink-2);
}

.tool-call-alert.is-error {
  color: oklch(55% 0.18 25);
  background: var(--danger-soft);
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
}

/* ── Search ───────────────────────────────────────────────────────── */
.search-field {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  border-radius: 0.625rem;
  border: 1px solid var(--hair);
  background: var(--paper);
  padding: 0.4rem 0.625rem;
  color: var(--ink-2);
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
  border: 1px solid var(--hair);
  background: var(--paper);
  overflow: hidden;
  transition: border-color 150ms ease;
}

.tool-call-entry[open] {
  border-color: var(--accent-line);
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
  background: color-mix(in oklab, var(--accent) 18%, transparent);
  color: var(--accent-ink);
  flex-shrink: 0;
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
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.entry-subtitle {
  font-size: 0.6875rem;
  color: var(--ink-2);
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
  background: color-mix(in oklab, var(--ink) 8%, transparent);
  color: var(--ink-2);
  font-size: 0.6875rem;
  font-variant-numeric: tabular-nums;
}

.meta-time {
  font-size: 0.6875rem;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}

.entry-chevron {
  color: var(--ink-2);
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
  background: color-mix(in oklab, var(--ink) 5%, transparent);
  padding: 0.5rem 0.625rem;
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
  color: var(--ink-2);
}

.payload-block pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.75rem;
  line-height: 1.25rem;
  color: var(--ink);
  font-family: var(--font-mono, monospace);
  max-height: 18rem;
  overflow: auto;
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
  color: var(--ink-2);
  font-size: 0.6875rem;
  font-weight: 500;
  transition: background-color 150ms ease;
}

.raw-json-button:hover {
  background: color-mix(in oklab, var(--ink) 6%, transparent);
  color: var(--ink);
}

.raw-json-block pre {
  max-height: 20rem;
}
</style>
