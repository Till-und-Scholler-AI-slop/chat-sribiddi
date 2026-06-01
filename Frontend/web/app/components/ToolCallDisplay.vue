<script setup lang="ts">
import type { ToolCallLog } from '~/composables/useChatApi'
import { filterToolCallLogs } from '~/composables/chat-helpers'

const { t } = useI18n()

const props = defineProps<{
  toolCalls: ToolCallLog[]
  loading: boolean
  error: string | null
}>()

const expanded = ref(true)
const filterQuery = ref('')

const filteredToolCalls = computed(() => filterToolCallLogs(props.toolCalls, filterQuery.value))

const toolTriggerLabel = computed(() => {
  if (props.loading && props.toolCalls.length === 0) return t('tools.running')
  const count = props.toolCalls.length
  if (count === 0) return t('tools.activity')
  if (count === 1) return displayName(props.toolCalls[0])
  return t('tools.callsCount', { count })
})

function toggle() {
  expanded.value = !expanded.value
}

function displayType(tc: ToolCallLog) {
  if (tc.displayType) return tc.displayType
  const name = tc.toolName.toLowerCase()
  if (name.includes('bravesearch') || name.includes('searchweb')) return 'web-search'
  if (name.includes('python')) return 'python'
  if (name.includes('image')) return 'image'
  if (name.includes('github')) return 'github'
  if (name.includes('googledrive')) return 'google-drive'
  if (name.includes('gmail')) return 'gmail'
  if (name.includes('calendar')) return 'calendar'
  if (name.includes('spreadsheet')) return 'spreadsheet'
  if (name.includes('webresearch')) return 'web-research'
  if (name.includes('chart')) return 'chart'
  if (name.includes('document')) return 'document'
  if (name.includes('agentplan')) return 'agent-plan'
  if (name.includes('urlfetch')) return 'url-fetcher'
  if (name.includes('datetime')) return 'date-time'
  if (name.includes('calculator')) return 'calculator'
  return 'generic'
}

function displayName(tc: ToolCallLog) {
  const key = `tools.display.${displayType(tc)}`
  const translated = t(key)
  if (translated !== key) return translated
  const raw = tc.toolName.split('.').pop() ?? tc.toolName
  return raw.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ')
}

function status(tc: ToolCallLog) {
  if (tc.status) return tc.status
  if (tc.errorMessage) return 'FAILED'
  if (tc.outputPayload || tc.durationMs != null) return 'SUCCESS'
  return 'RUNNING'
}

function statusIcon(tc: ToolCallLog) {
  const current = status(tc)
  if (current === 'FAILED') return 'i-heroicons-x-circle'
  if (current === 'RUNNING') return 'i-heroicons-arrow-path'
  return 'i-heroicons-check-circle'
}

function toolIcon(tc: ToolCallLog) {
  switch (displayType(tc)) {
    case 'web-search': return 'i-heroicons-magnifying-glass'
    case 'python': return 'i-heroicons-command-line'
    case 'image': return 'i-heroicons-photo'
    case 'github': return 'i-heroicons-code-bracket-square'
    case 'google-drive': return 'i-heroicons-folder-open'
    case 'gmail': return 'i-heroicons-envelope'
    case 'calendar': return 'i-heroicons-calendar-days'
    case 'spreadsheet': return 'i-heroicons-table-cells'
    case 'web-research': return 'i-heroicons-globe-alt'
    case 'chart': return 'i-heroicons-chart-bar'
    case 'document': return 'i-heroicons-document-magnifying-glass'
    case 'agent-plan': return 'i-heroicons-list-bullet'
    case 'url-fetcher': return 'i-heroicons-link'
    case 'date-time': return 'i-heroicons-clock'
    case 'calculator': return 'i-heroicons-calculator'
    default: return 'i-heroicons-wrench-screwdriver'
  }
}

function parsePayload(payload?: string | null): unknown {
  if (!payload) return null
  try {
    return JSON.parse(payload)
  } catch {
    return payload
  }
}

function inputSubject(tc: ToolCallLog) {
  const input = parsePayload(tc.inputPayload)
  if (input && typeof input === 'object' && !Array.isArray(input)) {
    const record = input as Record<string, unknown>
    const value =
      record.query ?? record.prompt ?? record.code ?? record.url ?? record.expression ?? record.currentStep
    if (value != null) return String(value).slice(0, 120)
  }
  return tc.toolName
}

function formatTimestamp(value?: string | null) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function formatPayload(payload?: string | null) {
  const parsed = parsePayload(payload)
  if (parsed == null) return ''
  return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2)
}

function parsedOutput(tc: ToolCallLog) {
  return parsePayload(tc.outputPayload)
}

function outputRecord(tc: ToolCallLog) {
  const parsed = parsedOutput(tc)
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
    ? parsed as Record<string, unknown>
    : null
}

function listValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
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
        :name="props.loading ? 'i-heroicons-arrow-path' : 'i-heroicons-wrench-screwdriver'"
        class="size-3 trigger-icon-inline"
        :class="{ 'animate-spin': props.loading }"
      />
      <span class="trigger-label">{{ toolTriggerLabel }}</span>
      <UIcon
        :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
        class="size-3 trigger-chevron"
      />
    </button>

    <div class="collapsible-content" :class="{ 'is-expanded': expanded }">
      <div class="collapsible-inner">
        <div class="tool-call-panel">
          <div v-if="props.error" class="tool-call-alert is-error">
            <UIcon name="i-heroicons-exclamation-circle" class="size-4 shrink-0" />
            <span>{{ props.error }}</span>
          </div>

          <div v-else-if="props.loading && props.toolCalls.length === 0" class="tool-call-alert">
            <UIcon name="i-heroicons-arrow-path" class="size-4 shrink-0 animate-spin" />
            <span>{{ t('tools.waitingResults') }}</span>
          </div>

          <template v-else>
            <div v-if="props.toolCalls.length > 2" class="panel-toolbar">
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
              <section
                v-for="(tc, index) in filteredToolCalls"
                :key="tc.id ?? tc.clientId ?? `${tc.toolName}-${index}`"
                class="tool-call-entry"
                :class="[`is-${status(tc).toLowerCase()}`, `type-${displayType(tc)}`]"
              >
                <header class="tool-call-entry-header">
                  <span class="entry-icon">
                    <UIcon :name="toolIcon(tc)" class="size-4" />
                  </span>
                  <span class="entry-titles">
                    <span class="entry-title">{{ displayName(tc) }}</span>
                    <span class="entry-subtitle">{{ inputSubject(tc) }}</span>
                  </span>
                  <span class="tool-call-meta">
                    <span class="status-chip">
                      <UIcon
                        :name="statusIcon(tc)"
                        class="size-3.5"
                        :class="{ 'animate-spin': status(tc) === 'RUNNING' }"
                      />
                      {{ t(`tools.status.${status(tc).toLowerCase()}`) }}
                    </span>
                    <span v-if="tc.durationMs != null" class="meta-pill">{{ tc.durationMs }}ms</span>
                    <span v-else-if="formatTimestamp(tc.startedAt)" class="meta-time">
                      {{ formatTimestamp(tc.startedAt) }}
                    </span>
                  </span>
                </header>

                <div class="tool-call-entry-body">
                  <template v-if="status(tc) === 'FAILED'">
                    <div class="error-block">
                      {{ tc.errorMessage || formatPayload(tc.outputPayload) || t('tools.errorFallback') }}
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'image'">
                    <div class="image-result">
                      <img
                        v-if="typeof outputRecord(tc)?.url === 'string'"
                        :src="outputRecord(tc)?.url as string"
                        :alt="inputSubject(tc)"
                      >
                      <div v-else class="artifact-summary">
                        {{ outputRecord(tc)?.message || t('tools.imagePending') }}
                      </div>
                      <div class="artifact-meta">
                        <span>{{ outputRecord(tc)?.model }}</span>
                        <span>{{ outputRecord(tc)?.size }}</span>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'github'">
                    <div class="result-list">
                      <a
                        v-for="item in listValue(outputRecord(tc)?.items).slice(0, 5)"
                        :key="(item as Record<string, unknown>).url as string"
                        class="result-row"
                        :href="(item as Record<string, unknown>).url as string"
                        target="_blank"
                        rel="noreferrer"
                      >
                        <span>{{ (item as Record<string, unknown>).title }}</span>
                        <small>#{{ (item as Record<string, unknown>).number }} · {{ (item as Record<string, unknown>).state }}</small>
                      </a>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'google-drive'">
                    <div class="artifact-summary">
                      <strong>{{ outputRecord(tc)?.filename || inputSubject(tc) }}</strong>
                      <span>{{ outputRecord(tc)?.mimeType }}</span>
                      <span v-if="outputRecord(tc)?.chunkCount != null">
                        {{ outputRecord(tc)?.chunkCount }} chunks
                      </span>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'gmail'">
                    <div class="result-list">
                      <div
                        v-for="item in listValue(outputRecord(tc)?.messages).slice(0, 5)"
                        :key="(item as Record<string, unknown>).id as string"
                        class="result-row is-static"
                      >
                        <span>{{ (item as Record<string, unknown>).subject || t('common.unknown') }}</span>
                        <small>{{ (item as Record<string, unknown>).from }} · {{ (item as Record<string, unknown>).date }}</small>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'calendar'">
                    <div class="result-list">
                      <div
                        v-for="item in listValue(outputRecord(tc)?.events ?? outputRecord(tc)?.busy).slice(0, 6)"
                        :key="JSON.stringify(item)"
                        class="result-row is-static"
                      >
                        <span>{{ (item as Record<string, unknown>).summary || (item as Record<string, unknown>).start }}</span>
                        <small>{{ (item as Record<string, unknown>).start }} – {{ (item as Record<string, unknown>).end }}</small>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'spreadsheet'">
                    <div class="plan-result">
                      <strong>{{ outputRecord(tc)?.filename }}</strong>
                      <div class="artifact-meta">
                        <span>{{ outputRecord(tc)?.rows }} rows</span>
                        <span>{{ outputRecord(tc)?.columns }} columns</span>
                      </div>
                      <ul>
                        <li
                          v-for="column in listValue(outputRecord(tc)?.schema).slice(0, 8)"
                          :key="(column as Record<string, unknown>).name as string"
                        >
                          {{ (column as Record<string, unknown>).name }} · {{ (column as Record<string, unknown>).type }}
                        </li>
                      </ul>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'web-research'">
                    <div class="result-list">
                      <a
                        v-for="source in listValue(outputRecord(tc)?.sources).slice(0, 5)"
                        :key="(source as Record<string, unknown>).url as string"
                        class="result-row"
                        :href="(source as Record<string, unknown>).url as string"
                        target="_blank"
                        rel="noreferrer"
                      >
                        <span>{{ (source as Record<string, unknown>).title }}</span>
                        <small>{{ (source as Record<string, unknown>).url }}</small>
                      </a>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'chart'">
                    <div class="chart-result">
                      <div
                        v-if="typeof outputRecord(tc)?.svg === 'string'"
                        class="chart-svg"
                        v-html="outputRecord(tc)?.svg"
                      />
                      <p>{{ outputRecord(tc)?.summary }}</p>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'document'">
                    <div class="result-list">
                      <div
                        v-for="chunk in listValue(outputRecord(tc)?.chunks)"
                        :key="`${(chunk as Record<string, unknown>).documentId}-${(chunk as Record<string, unknown>).chunkIndex}`"
                        class="result-row is-static"
                      >
                        <span>{{ (chunk as Record<string, unknown>).excerpt }}</span>
                        <small>
                          {{ t('tools.documentChunk', { chunk: (chunk as Record<string, unknown>).chunkIndex }) }}
                        </small>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="displayType(tc) === 'agent-plan'">
                    <div class="plan-result">
                      <div class="plan-progress">
                        {{ outputRecord(tc)?.step }} / {{ outputRecord(tc)?.totalSteps }}
                      </div>
                      <strong>{{ outputRecord(tc)?.currentStep }}</strong>
                      <ul>
                        <li v-for="step in listValue(outputRecord(tc)?.completedSteps)" :key="String(step)">
                          {{ step }}
                        </li>
                      </ul>
                    </div>
                  </template>

                  <template v-else>
                    <pre class="text-output">{{ formatPayload(tc.outputPayload || tc.inputPayload) }}</pre>
                  </template>
                </div>
              </section>
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

.tool-call-trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.125rem 0;
  border: none;
  background: transparent;
  color: var(--ink-2);
  font-size: 0.75rem;
  line-height: 1rem;
  cursor: pointer;
}

.tool-call-trigger:hover {
  color: var(--ink);
}

.trigger-icon-inline,
.trigger-chevron {
  flex-shrink: 0;
}

.collapsible-content {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 180ms ease;
}

.collapsible-content.is-expanded {
  grid-template-rows: 1fr;
}

.collapsible-inner {
  overflow: hidden;
}

.tool-call-panel {
  margin-top: 0.5rem;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: var(--paper-2);
  padding: 0.5rem;
}

.tool-call-alert,
.tool-call-empty {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--ink-2);
  font-size: 0.8125rem;
}

.tool-call-alert.is-error,
.error-block {
  color: oklch(55% 0.18 25);
  background: var(--danger-soft);
}

.panel-toolbar {
  margin-bottom: 0.5rem;
}

.search-field {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: var(--paper);
  padding: 0.375rem 0.5rem;
  color: var(--ink-2);
}

.search-input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: inherit;
  font-size: 0.8125rem;
}

.tool-call-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.tool-call-entry {
  overflow: hidden;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: var(--paper);
}

.tool-call-entry-header {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-height: 2.75rem;
  padding: 0.5rem 0.625rem;
}

.entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.375rem;
  background: color-mix(in oklab, var(--accent) 14%, transparent);
  color: var(--accent-ink);
  flex-shrink: 0;
}

.entry-titles {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.0625rem;
}

.entry-title {
  overflow: hidden;
  color: var(--ink);
  font-size: 0.8125rem;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-subtitle {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 0.71875rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-call-meta {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  flex-shrink: 0;
}

.status-chip,
.meta-pill,
.meta-time {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  border: 1px solid var(--hair);
  border-radius: 999px;
  background: color-mix(in oklab, var(--ink) 4%, transparent);
  color: var(--ink-2);
  padding: 0.125rem 0.375rem;
  font-size: 0.6875rem;
  font-variant-numeric: tabular-nums;
}

.is-success .status-chip {
  color: var(--accent-ink);
  border-color: var(--accent-line);
}

.is-failed .status-chip {
  color: oklch(55% 0.18 25);
}

.tool-call-entry-body {
  border-top: 1px solid var(--hair);
  padding: 0.625rem;
}

.text-output,
.error-block,
.artifact-summary {
  margin: 0;
  border-radius: 0.375rem;
  background: color-mix(in oklab, var(--ink) 5%, transparent);
  padding: 0.5rem;
  color: var(--ink);
  font-family: var(--font-mono, monospace);
  font-size: 0.75rem;
  line-height: 1.35;
  white-space: pre-wrap;
  word-break: break-word;
}

.result-list {
  display: grid;
  gap: 0.375rem;
}

.result-row {
  display: grid;
  gap: 0.125rem;
  border-radius: 0.375rem;
  background: color-mix(in oklab, var(--ink) 4%, transparent);
  padding: 0.5rem;
  color: var(--ink);
  text-decoration: none;
}

.result-row:hover:not(.is-static) {
  background: color-mix(in oklab, var(--accent) 10%, transparent);
}

.result-row small,
.artifact-meta {
  color: var(--ink-2);
  font-size: 0.6875rem;
}

.image-result {
  display: grid;
  gap: 0.5rem;
}

.image-result img {
  max-width: min(100%, 22rem);
  border-radius: 0.5rem;
  border: 1px solid var(--hair);
}

.artifact-meta {
  display: flex;
  gap: 0.5rem;
}

.chart-result {
  display: grid;
  gap: 0.5rem;
}

.chart-svg {
  overflow: hidden;
  max-width: 100%;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: white;
}

.chart-svg :deep(svg) {
  display: block;
  width: 100%;
  height: auto;
}

.chart-result p {
  margin: 0;
  color: var(--ink-2);
  font-size: 0.8125rem;
}

.plan-result {
  display: grid;
  gap: 0.375rem;
  color: var(--ink);
  font-size: 0.8125rem;
}

.plan-progress {
  color: var(--ink-2);
  font-size: 0.6875rem;
}

.plan-result ul {
  margin: 0;
  padding-left: 1rem;
}

@media (max-width: 768px) {
  .tool-call-entry-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .tool-call-meta {
    width: 100%;
    padding-left: 2.375rem;
    flex-wrap: wrap;
  }
}
</style>
