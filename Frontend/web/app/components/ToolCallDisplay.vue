<script setup lang="ts">
import type { ToolCallLog } from '~/composables/useChatApi'

const props = defineProps<{
  toolCalls: ToolCallLog[]
  loading: boolean
  error: string | null
}>()

type ParsedSearchResult = {
  title: string
  url: string
  domain: string
  description: string
  favicon: string
}

type ParsedWebSearch = {
  query: string
  results: ParsedSearchResult[]
}

const OUTPUT_HEADER = 'Web search results'

const isWebSearch = computed(() =>
  props.toolCalls.length > 0 && props.toolCalls.some(tc => tc.toolName.toLowerCase().includes('search'))
)

const webSearchData = computed((): ParsedWebSearch | null => {
  if (!isWebSearch.value) return null
  const tc = props.toolCalls.find(t => t.toolName.toLowerCase().includes('search'))
  if (!tc) return null

  let query = ''
  try {
    const input = JSON.parse(tc.inputPayload)
    query = input.query || input.q || ''
  } catch {
    query = tc.inputPayload
  }

  const results: ParsedSearchResult[] = []
  const output = tc.outputPayload || ''

  // Parse the formatted output: "1. **title**\n   URL: url\n   description"
  const lines = output.split('\n')
  let currentTitle = ''
  let currentUrl = ''
  let currentDesc = ''

  for (const line of lines) {
    const trimmed = line.trim()

    // Match numbered result: "1. **Title**"
    const titleMatch = trimmed.match(/^\d+\.\s+\*\*(.+?)\*\*$/)
    if (titleMatch) {
      // Save previous result if exists
      if (currentTitle && currentUrl) {
        const domain = extractDomain(currentUrl)
        results.push({
          title: currentTitle,
          url: currentUrl,
          domain,
          description: currentDesc.trim(),
          favicon: extractOrigin(currentUrl) + '/favicon.ico'
        })
      }
      currentTitle = titleMatch[1]
      currentUrl = ''
      currentDesc = ''
      continue
    }

    // Match URL line: "URL: https://..."
    const urlMatch = trimmed.match(/^URL:\s+(.+)$/)
    if (urlMatch) {
      currentUrl = urlMatch[1]
      continue
    }

    // Anything else is description (if we have a title)
    if (currentTitle && trimmed && !trimmed.startsWith(OUTPUT_HEADER)) {
      if (currentDesc) currentDesc += ' '
      currentDesc += trimmed
    }
  }

  // Push last result
  if (currentTitle && currentUrl) {
    const domain = extractDomain(currentUrl)
    results.push({
      title: currentTitle,
      url: currentUrl,
      domain,
      description: currentDesc.trim(),
      favicon: extractOrigin(currentUrl) + '/favicon.ico'
    })
  }

  return { query, results }
})

const nonSearchToolCalls = computed(() =>
  props.toolCalls.filter(tc => !tc.toolName.toLowerCase().includes('search'))
)

function extractDomain(url: string): string {
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}

function extractOrigin(url: string): string {
  try {
    return new URL(url).origin
  } catch {
    return url
  }
}

const expanded = ref(false)
const brokenFavicons = ref(new Set<number>())

function toggle() {
  expanded.value = !expanded.value
}

function onFaviconError(index: number) {
  brokenFavicons.value.add(index)
}
</script>

<template>
  <div class="mt-3">
    <!-- Web Search Display -->
    <template v-if="loading">
      <div class="tool-call-container">
        <div class="flex items-center gap-2 px-3 py-2.5">
          <div class="tool-call-icon-wrapper">
            <UIcon name="i-heroicons-globe-alt" class="size-4 text-gray-400 dark:text-gray-500 animate-pulse" />
          </div>
          <span class="text-sm text-gray-500 dark:text-gray-400">Searching the web…</span>
        </div>
      </div>
    </template>

    <template v-else-if="error">
      <div class="tool-call-container">
        <div class="flex items-center gap-2 px-3 py-2.5 text-red-400 dark:text-red-300">
          <UIcon name="i-heroicons-exclamation-circle" class="size-4" />
          <span class="text-sm">{{ error }}</span>
        </div>
      </div>
    </template>

    <template v-else-if="webSearchData">
      <div class="tool-call-container">
        <button
          class="w-full flex items-center gap-2 px-3 py-2.5 text-sm transition-colors hover:bg-gray-100/50 dark:hover:bg-white/[0.03] rounded-xl"
          @click="toggle"
        >
          <div class="tool-call-icon-wrapper">
            <UIcon name="i-heroicons-globe-alt" class="size-4 text-gray-500 dark:text-gray-400" />
          </div>
          <span class="text-gray-600 dark:text-gray-300 font-medium">Web searched</span>
          <UIcon
            :name="expanded ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
            class="size-3.5 text-gray-400 dark:text-gray-500 ml-auto transition-transform duration-200"
          />
        </button>

        <div class="collapsible-content" :class="{ 'is-expanded': expanded }">
          <div class="collapsible-inner">
            <div class="px-3 pb-3">
              <!-- Search query header -->
              <div class="flex items-center justify-between mb-2 px-1">
                <div class="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
                  <UIcon name="i-heroicons-magnifying-glass" class="size-3.5" />
                  <span class="truncate">{{ webSearchData.query }}</span>
                </div>
                <span class="text-xs text-gray-400 dark:text-gray-500 flex-shrink-0">
                  {{ webSearchData.results.length }} {{ webSearchData.results.length === 1 ? 'result' : 'results' }}
                </span>
              </div>

              <!-- Search results list -->
              <div class="search-results-list rounded-lg border border-gray-200/80 dark:border-white/[0.06] overflow-hidden">
                <a
                  v-for="(result, idx) in webSearchData.results"
                  :key="idx"
                  :href="result.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="search-result-item flex items-center gap-3 px-3 py-2.5 transition-colors hover:bg-gray-100/60 dark:hover:bg-white/[0.03]"
                  :class="{ 'border-t border-gray-200/60 dark:border-white/[0.04]': idx > 0 }"
                >
                  <img
                    v-if="!brokenFavicons.has(idx)"
                    :src="result.favicon"
                    :alt="result.domain"
                    class="size-4 rounded-sm flex-shrink-0"
                    loading="lazy"
                    @error="onFaviconError(idx)"
                  >
                  <span class="flex-1 truncate text-sm text-gray-700 dark:text-gray-300">
                    {{ result.title }}
                  </span>
                  <span class="text-xs text-gray-400 dark:text-gray-500 flex-shrink-0 hidden sm:inline">
                    {{ result.domain }}
                  </span>
                </a>
              </div>

              <!-- Done indicator -->
              <div class="flex items-center gap-2 mt-2.5 px-1 text-xs text-gray-400 dark:text-gray-500">
                <UIcon name="i-heroicons-check-circle" class="size-3.5 text-emerald-500/70" />
                <span>Done</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Non-search tool calls (generic display) -->
    <template v-if="nonSearchToolCalls.length > 0">
      <div
        v-for="tc in nonSearchToolCalls"
        :key="tc.id ?? tc.toolName"
        class="tool-call-container mt-2"
      >
        <div class="flex items-center gap-2 px-3 py-2.5 text-sm">
          <div class="tool-call-icon-wrapper">
            <UIcon name="i-heroicons-wrench-screwdriver" class="size-4 text-gray-500 dark:text-gray-400" />
          </div>
          <span class="text-gray-600 dark:text-gray-300 font-medium">{{ tc.toolName }}</span>
          <span v-if="tc.durationMs" class="text-xs text-gray-400 dark:text-gray-500 ml-auto">
            {{ tc.durationMs }}ms
          </span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tool-call-container {
  border-radius: 0.75rem;
  border: 1px solid color-mix(in srgb, currentColor 6%, transparent);
  background: color-mix(in srgb, currentColor 2%, transparent);
}

.tool-call-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  flex-shrink: 0;
}

/* Smooth collapsible section using grid rows */
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

.search-results-list {
  max-height: 280px;
  overflow-y: auto;
  scrollbar-width: thin;
}
</style>
