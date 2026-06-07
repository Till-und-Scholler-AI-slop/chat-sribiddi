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
  if (!payload) return t('tools.emptyValue')

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
  if (tc.status === 'ERROR') return 'i-heroicons-exclamation-circle'
  if (tc.status === 'RUNNING') return 'i-heroicons-arrow-path'
  return 'i-heroicons-check-circle'
}

function statusLabel(tc: ToolCallLog) {
  if (tc.status === 'ERROR') return t('tools.statusError')
  if (tc.status === 'RUNNING') return t('tools.statusRunning')
  return t('tools.statusSuccess')
}

function statusClass(tc: ToolCallLog) {
  if (tc.status === 'ERROR') return 'is-error'
  if (tc.status === 'RUNNING') return 'is-running'
  return 'is-success'
}

function parsePayload(payload?: string | null): unknown {
  if (!payload) return null
  try {
    return JSON.parse(payload)
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parsedOutput(tc: ToolCallLog) {
  return parsePayload(tc.outputPayload)
}

function chartDataUrl(tc: ToolCallLog) {
  const parsed = parsedOutput(tc)
  if (!isRecord(parsed) || typeof parsed.svg !== 'string') return null
  const svg = parsed.svg.trim()
  if (!svg.startsWith('<svg') || svg.length > 250_000) return null
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

function searchResults(tc: ToolCallLog) {
  const parsed = parsedOutput(tc)
  const rawResults = Array.isArray(parsed)
    ? parsed
    : isRecord(parsed) && Array.isArray(parsed.results)
      ? parsed.results
      : []
  return rawResults.flatMap(item => {
    if (!isRecord(item) || typeof item.title !== 'string' || typeof item.url !== 'string') {
      return []
    }
    return [{
      title: item.title,
      url: item.url,
      description: typeof item.description === 'string' ? item.description : ''
    }]
  }).slice(0, 8)
}

function tableAnalysis(tc: ToolCallLog) {
  const parsed = parsedOutput(tc)
  if (!isRecord(parsed)) return null
  const columns = Array.isArray(parsed.columns)
    ? parsed.columns.filter((item): item is string => typeof item === 'string')
    : []
  const preview = Array.isArray(parsed.preview)
    ? parsed.preview.filter(isRecord).slice(0, 8)
    : []
  const numericColumns = Array.isArray(parsed.numericColumns)
    ? parsed.numericColumns.filter(isRecord)
    : []
  if (!columns.length && !preview.length) return null
  return {
    rowCount: typeof parsed.rowCount === 'number' ? parsed.rowCount : null,
    columnCount: typeof parsed.columnCount === 'number' ? parsed.columnCount : columns.length,
    columns,
    preview,
    numericColumns
  }
}

function planSteps(tc: ToolCallLog) {
  const parsed = parsedOutput(tc)
  if (!isRecord(parsed) || !Array.isArray(parsed.steps)) return []
  return parsed.steps.flatMap(item => {
    if (!isRecord(item) || typeof item.title !== 'string') return []
    return [{
      index: typeof item.index === 'number' ? item.index : null,
      title: item.title,
      notes: typeof item.notes === 'string' ? item.notes : null,
      status: typeof item.status === 'string' ? item.status : null
    }]
  })
}

function numericValue(value: unknown) {
  return typeof value === 'number'
    ? value.toLocaleString(undefined, { maximumFractionDigits: 3 })
    : t('tools.emptyValue')
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
      <UIcon
        :name="loading ? 'i-heroicons-arrow-path' : 'i-heroicons-wrench-screwdriver'"
        class="size-3 trigger-icon-inline"
        :class="{ 'animate-spin': loading }"
      />
      <span class="trigger-label">{{ toolTriggerLabel }}</span>
      <span v-if="!loading && toolCalls.length > 1" class="tool-call-count">
        ({{ toolCalls.length }})
      </span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3 trigger-chevron"
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
                  <span class="entry-status" :class="statusClass(tc)">
                    <UIcon
                      :name="statusIcon(tc)"
                      class="size-4"
                      :class="{ 'animate-spin': tc.status === 'RUNNING' }"
                    />
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
                    <span class="meta-pill" :class="statusClass(tc)">
                      {{ statusLabel(tc) }}
                    </span>
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
                  <div v-if="tc.errorMessage" class="tool-call-alert is-error">
                    <UIcon name="i-heroicons-exclamation-circle" class="size-4 shrink-0" />
                    <span>{{ tc.errorMessage }}</span>
                  </div>

                  <div class="payload-block">
                    <div class="payload-label">
                      <UIcon name="i-heroicons-arrow-up-tray" class="size-3" />
                      {{ t('tools.input') }}
                    </div>
                    <pre>{{ formatPayload(tc.inputPayload) }}</pre>
                  </div>

                  <div v-if="tc.status === 'RUNNING'" class="tool-call-alert">
                    <UIcon name="i-heroicons-arrow-path" class="size-4 shrink-0 animate-spin" />
                    <span>{{ t('tools.toolRunning') }}</span>
                  </div>

                  <div v-else-if="tc.displayType === 'chart' && chartDataUrl(tc)" class="tool-output-card">
                    <img
                      :src="chartDataUrl(tc) || undefined"
                      :alt="formatToolNameForDisplay(tc.toolName)"
                      class="chart-output"
                    >
                  </div>

                  <div
                    v-else-if="tc.displayType === 'search-results' && searchResults(tc).length"
                    class="tool-output-card result-list"
                  >
                    <a
                      v-for="result in searchResults(tc)"
                      :key="result.url"
                      :href="result.url"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="result-item"
                    >
                      <span class="result-title">{{ result.title }}</span>
                      <span class="result-url">{{ result.url }}</span>
                      <span v-if="result.description" class="result-description">
                        {{ result.description }}
                      </span>
                    </a>
                  </div>

                  <div
                    v-else-if="tc.displayType === 'table' && tableAnalysis(tc)"
                    class="tool-output-card table-output"
                  >
                    <div class="table-summary">
                      {{ t('tools.tableSummary', {
                        rows: tableAnalysis(tc)?.rowCount ?? 0,
                        columns: tableAnalysis(tc)?.columnCount ?? 0
                      }) }}
                    </div>
                    <div class="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th
                              v-for="column in tableAnalysis(tc)?.columns"
                              :key="column"
                            >
                              {{ column }}
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="(row, rowIndex) in tableAnalysis(tc)?.preview"
                            :key="rowIndex"
                          >
                            <td
                              v-for="column in tableAnalysis(tc)?.columns"
                              :key="column"
                            >
                              {{ row[column] ?? t('tools.emptyValue') }}
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <div
                      v-if="tableAnalysis(tc)?.numericColumns.length"
                      class="numeric-stat-list"
                    >
                      <div
                        v-for="column in tableAnalysis(tc)?.numericColumns"
                        :key="String(column.column)"
                        class="numeric-stat"
                      >
                        <span class="numeric-stat-title">{{ column.column }}</span>
                        <span>{{ t('tools.minValue', { value: numericValue(column.min) }) }}</span>
                        <span>{{ t('tools.maxValue', { value: numericValue(column.max) }) }}</span>
                        <span>{{ t('tools.averageValue', { value: numericValue(column.average) }) }}</span>
                      </div>
                    </div>
                  </div>

                  <div
                    v-else-if="tc.displayType === 'plan' && planSteps(tc).length"
                    class="tool-output-card plan-output"
                  >
                    <ol>
                      <li v-for="step in planSteps(tc)" :key="step.index ?? step.title">
                        <span class="plan-title">{{ step.title }}</span>
                        <span v-if="step.notes" class="plan-notes">{{ step.notes }}</span>
                      </li>
                    </ol>
                  </div>

                  <div v-else class="payload-block">
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

/* ── Trigger text link ─────────────────────────────────────────────── */
.tool-call-trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.125rem 0;
  border: none;
  background: transparent;
  border-radius: 0;
  font-size: 0.75rem;
  line-height: 1rem;
  color: var(--ink-2);
  cursor: pointer;
  transition: color 150ms ease;
}

.tool-call-trigger:hover {
  color: var(--ink);
}

.trigger-icon-inline {
  color: var(--ink-2);
  flex-shrink: 0;
}

.trigger-label {
  font-weight: 400;
}

.trigger-chevron {
  opacity: 0.5;
  transition: transform 200ms ease;
}

.tool-call-trigger[aria-expanded="true"] .trigger-chevron {
  transform: rotate(0deg);
}

.tool-call-count {
  font-size: 0.6875rem;
  color: var(--ink-2);
  font-weight: 400;
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

.entry-status.is-success,
.meta-pill.is-success {
  background: color-mix(in oklab, var(--success, oklch(58% 0.16 145)) 16%, transparent);
  color: var(--success, oklch(44% 0.14 145));
}

.entry-status.is-running,
.meta-pill.is-running {
  background: color-mix(in oklab, var(--accent) 18%, transparent);
  color: var(--accent-ink);
}

.entry-status.is-error,
.meta-pill.is-error {
  background: var(--danger-soft);
  color: oklch(48% 0.18 25);
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

.tool-output-card {
  border-radius: 0.625rem;
  border: 1px solid var(--hair);
  background: var(--paper);
  overflow: hidden;
}

.chart-output {
  display: block;
  width: 100%;
  max-height: 22rem;
  object-fit: contain;
  background: white;
}

.result-list {
  display: flex;
  flex-direction: column;
}

.result-item {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid var(--hair);
  color: inherit;
  text-decoration: none;
}

.result-item:last-child {
  border-bottom: 0;
}

.result-item:hover .result-title {
  color: var(--accent-ink);
}

.result-title {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--ink);
}

.result-url,
.result-description {
  font-size: 0.75rem;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.table-output {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  padding: 0.625rem;
}

.table-summary {
  font-size: 0.75rem;
  color: var(--ink-2);
}

.table-scroll {
  overflow-x: auto;
}

.table-output table {
  width: 100%;
  min-width: 24rem;
  border-collapse: collapse;
  font-size: 0.75rem;
}

.table-output th,
.table-output td {
  padding: 0.35rem 0.5rem;
  border: 1px solid var(--hair);
  text-align: left;
  vertical-align: top;
}

.table-output th {
  background: color-mix(in oklab, var(--ink) 6%, transparent);
  color: var(--ink);
  font-weight: 600;
}

.numeric-stat-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  gap: 0.5rem;
}

.numeric-stat {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  border-radius: 0.5rem;
  background: color-mix(in oklab, var(--ink) 5%, transparent);
  padding: 0.5rem;
  font-size: 0.72rem;
  color: var(--ink-2);
}

.numeric-stat-title,
.plan-title {
  color: var(--ink);
  font-weight: 600;
}

.plan-output {
  padding: 0.625rem 0.75rem;
}

.plan-output ol {
  margin: 0;
  padding-left: 1.25rem;
}

.plan-output li {
  margin: 0.35rem 0;
}

.plan-notes {
  display: block;
  margin-top: 0.1rem;
  font-size: 0.75rem;
  color: var(--ink-2);
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

@media (max-width: 768px) {
  .tool-call-entry-summary {
    flex-wrap: wrap;
    padding: 0.75rem;
  }

  .tool-call-meta {
    flex-wrap: wrap;
    gap: 0.375rem;
  }

  .tool-call-panel {
    padding: 0.75rem;
  }

  .payload-block pre {
    font-size: 0.6875rem;
  }
}
</style>
