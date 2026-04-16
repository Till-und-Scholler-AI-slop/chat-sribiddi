<script setup lang="ts">
import type { MediaAttachment, ModelOption } from '~/composables/useChatApi'
import { calculateMessageCost, estimatePreSendCost, formatCost, formatRatePerMillion } from '~/composables/useChatStore'

definePageMeta({
  layout: 'default'
})

const {
  activeConversation,
  activeChatId,
  conversations,
  defaultModel,
  editMessage,
  ensureModels,
  isFavorite,
  loadChatsFromBackend,
  loadMessagesForChat,
  messagePricingMap,
  models,
  modelsError,
  modelsLoading,
  reasoningMap,
  retryLastPrompt,
  saveLastUsedModel,
  sendPrompt,
  hasToolCalls,
  getToolCalls,
  getToolCallError,
  isToolCallLoading,
  ensureToolCalls,
  setModelForChat,
  sortedModels,
  startChat,
  stop,
  tokenUsageMap,
  webSearchEnabled
} = useChatStore()
const { settings } = useUserSettings()

const input = ref('')
const attachments = ref<MediaAttachment[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const initializing = ref(true)
const toast = useToast()
const messagesContainer = ref<HTMLElement | null>(null)
const skipNextSmoothScroll = ref(false)
let streamingRafId: number | null = null

const scrollToBottom = (behavior: 'smooth' | 'auto' = 'smooth') => {
  nextTick(() => {
    const el = messagesContainer.value
    if (el) {
      el.scrollTo({ top: el.scrollHeight, behavior })
    }
  })
}

// Edit state
const editingMessageId = ref<string | null>(null)
const editingText = ref('')

const selectedModel = computed<ModelOption | null>({
  get: () => {
    const conversation = activeConversation.value
    if (conversation?.model) return conversation.model
    return defaultModel.value
  },
  set: value => {
    if (value && activeConversation.value) {
      setModelForChat(activeConversation.value.session.chatId, value)
      saveLastUsedModel(value)
    }
  }
})

const status = computed(() => activeConversation.value?.status || 'ready')
const errorMessage = computed(() => activeConversation.value?.error || modelsError.value)
const promptError = computed(() =>
  errorMessage.value ? new Error(errorMessage.value) : undefined
)

const firstText = (parts?: Array<{ type: string; text?: string }>) =>
  parts?.find(part => part.type === 'text' && typeof part.text === 'string')?.text || ''

type FilePart = { type: 'file'; mediaType: string; filename?: string }

const getFileParts = (parts?: Array<{ type: string }>): FilePart[] =>
  (parts || []).filter((p): p is FilePart => p.type === 'file')

const formatMimeType = (mimeType: string): string => {
  const subType = mimeType.split('/').pop() || mimeType
  return subType.toUpperCase()
}

const formatTokenCount = (count: number | null | undefined): string => {
  if (count == null) return '—'
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`
  return count.toLocaleString()
}

const getTokenUsage = (messageId: string) => tokenUsageMap.value[messageId] ?? null

const getMessageCost = (messageId: string) =>
  calculateMessageCost(getTokenUsage(messageId), messagePricingMap.value[messageId] ?? null)

const getReasoning = (messageId: string) => reasoningMap.value[messageId] ?? null

const formatTimestamp = (date: Date | string | undefined): string => {
  if (!date) return ''
  const d = date instanceof Date ? date : new Date(date)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })
}

/**
 * Returns true if a UI message id corresponds to a persisted backend message
 * (i.e. has the "msg-{numericId}" format with a digits-only suffix).
 */
const isPersistedMessage = (id: string | undefined) =>
  typeof id === 'string' && /^msg-\d+$/.test(id)

const showEditMessageButton = (message?: { role?: string; id?: string }) =>
  message?.role === 'user' && isPersistedMessage(message.id) && status.value === 'ready'

const reasoningExpanded = ref<Record<string, boolean>>({})

const fileIcon = (mimeType: string): string => {
  if (mimeType.startsWith('image/')) return 'i-heroicons-photo'
  if (mimeType === 'application/pdf') return 'i-heroicons-document-text'
  return 'i-heroicons-paper-clip'
}

const providerLabel = (provider: ModelOption['provider']) =>
  provider === 'OPENAI'
    ? 'OpenAI'
    : provider === 'GOOGLE'
      ? 'Google'
      : provider === 'CLAUDE'
        ? 'Claude'
        : 'OpenRouter'

const isLastMessage = (message?: { id?: string }) => {
  const msgs = activeConversation.value?.messages
  if (!msgs || !message?.id) return false
  return msgs[msgs.length - 1]?.id === message.id
}

const modelPricingSummary = computed(() => {
  const model = selectedModel.value
  const p = formatRatePerMillion(model?.promptPrice)
  const c = formatRatePerMillion(model?.completionPrice)
  if (!p && !c) return null

  const parts: string[] = []
  if (p != null) parts.push(`${p} in`)
  if (c != null) parts.push(`${c} out`)
  return parts.length ? `${parts.join(' / ')} pro 1M Tokens` : null
})

const optionPriceLabel = (option: ModelOption): string | null =>
  formatRatePerMillion(option.promptPrice)

const pendingPromptText = computed(() => {
  const trimmed = input.value.trim()
  if (trimmed) return trimmed
  return attachments.value.length ? 'Bitte analysiere den Anhang.' : ''
})

const preSendCostEstimate = computed(() => {
  if (!settings.value.showPreSendCostEstimate || status.value !== 'ready') return null

  const conversation = activeConversation.value
  const model = selectedModel.value
  const pendingText = pendingPromptText.value

  if (!conversation || !model || !pendingText) return null

  return estimatePreSendCost(
    conversation.messages,
    pendingText,
    model,
    tokenUsageMap.value,
    attachments.value.length
  )
})

const preSendPromptRateLabel = computed(() =>
  formatRatePerMillion(selectedModel.value?.promptPrice)
)

const preSendCompletionRateLabel = computed(() =>
  formatRatePerMillion(selectedModel.value?.completionPrice)
)

const toDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(new Error(`Datei ${file.name} konnte nicht gelesen werden.`))
    reader.readAsDataURL(file)
  })

const isDragOver = ref(false)

const clearAttachments = () => {
  attachments.value = []
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

const removeAttachment = (index: number) => {
  attachments.value.splice(index, 1)
}

const processFileList = async (files: File[]) => {
  if (files.length === 0) return
  const results = await Promise.allSettled(
    files.map(async file => ({
      name: file.name,
      mimeType: file.type || 'application/octet-stream',
      data: await toDataUrl(file)
    }))
  )
  for (const result of results) {
    if (result.status === 'fulfilled') {
      attachments.value.push(result.value)
    } else {
      toast.add({
        title: 'Upload fehlgeschlagen',
        description: result.reason instanceof Error ? result.reason.message : 'Anhang konnte nicht gelesen werden.',
        color: 'error'
      })
    }
  }
}

const handleFiles = async (event: Event) => {
  const inputNode = event.target as HTMLInputElement
  const files = Array.from(inputNode.files || [])
  await processFileList(files)
  inputNode.value = ''
}

const handleDragOver = (event: DragEvent) => {
  const types = event.dataTransfer?.types
  if (types && Array.from(types).includes('Files')) {
    event.preventDefault()
    isDragOver.value = true
  }
}

const handleDragLeave = (event: DragEvent) => {
  // Clear the drag state only when the drag pointer leaves the browser window
  if (!event.relatedTarget) {
    isDragOver.value = false
  }
}

const handleDrop = async (event: DragEvent) => {
  event.preventDefault()
  isDragOver.value = false
  const types = event.dataTransfer?.types
  if (!types || !Array.from(types).includes('Files')) {
    return
  }
  const files = Array.from(event.dataTransfer?.files || [])
  await processFileList(files)
}

const handlePaste = async (event: ClipboardEvent) => {
  const files = Array.from(event.clipboardData?.files || [])
  if (files.length > 0) {
    event.preventDefault()
    await processFileList(files)
  }
}

onMounted(async () => {
  document.addEventListener('paste', handlePaste)
  document.addEventListener('dragover', handleDragOver)
  document.addEventListener('dragleave', handleDragLeave)
  document.addEventListener('drop', handleDrop)
  try {
    await ensureModels()
    await loadChatsFromBackend()
    if (conversations.value.length === 0) {
      await startChat()
    }

    if (activeConversation.value && !activeConversation.value.model && defaultModel.value) {
      setModelForChat(activeConversation.value.session.chatId, defaultModel.value)
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Chat konnte nicht gestartet werden',
      description: err instanceof Error ? err.message : 'Läuft das Backend?',
      color: 'error'
    })
  } finally {
    initializing.value = false
  }
})

onUnmounted(() => {
  document.removeEventListener('paste', handlePaste)
  document.removeEventListener('dragover', handleDragOver)
  document.removeEventListener('dragleave', handleDragLeave)
  document.removeEventListener('drop', handleDrop)
})

watch(activeChatId, async newId => {
  if (!newId) return
  const conversation = conversations.value.find(c => c.session.chatId === newId)
  if (!conversation) return

  if (!conversation.model && defaultModel.value) {
    setModelForChat(conversation.session.chatId, defaultModel.value)
  }

  if (conversation.messages.length === 0) {
    skipNextSmoothScroll.value = true
    await loadMessagesForChat(newId)
  }
  scrollToBottom('auto')
})

// Scroll smoothly when a new message is added to the conversation
watch(
  () => activeConversation.value?.messages?.length,
  (newLength) => {
    if (!newLength) return
    if (skipNextSmoothScroll.value) {
      skipNextSmoothScroll.value = false
      return
    }
    scrollToBottom('smooth')
  },
)

// Auto-load tool calls when a message indicates it has them
watch(
  () => activeConversation.value?.messages?.map(msg => ({
    id: msg.id,
    role: msg.role,
    hasTools: hasToolCalls(msg.id),
    toolCount: getToolCalls(msg.id).length,
  })),
  (newSnapshots, oldSnapshots) => {
    if (!newSnapshots) return

    for (const snapshot of newSnapshots) {
      if (snapshot.role !== 'assistant') continue
      if (!snapshot.hasTools) continue
      if (snapshot.toolCount !== 0) continue
      if (isToolCallLoading(snapshot.id)) continue

      const prev = oldSnapshots?.find(p => p.id === snapshot.id)
      const justBecameToolAware =
        !prev || (prev.hasTools === false && snapshot.hasTools === true)

      if (justBecameToolAware) {
        ensureToolCalls(snapshot.id).catch(() => {
          // error surfaced through store state
        })
      }
    }
  },
)

// Scroll while streaming (last message text grows in place), throttled per animation frame
watch(
  () => {
    const msgs = activeConversation.value?.messages
    const last = msgs?.[msgs.length - 1]
    return last ? firstText(last.parts) : undefined
  },
  (newText) => {
    if (!newText) return
    if (streamingRafId !== null) return
    streamingRafId = requestAnimationFrame(() => {
      streamingRafId = null
      scrollToBottom('auto')
    })
  },
)

async function handleSubmit() {
  if (!input.value.trim() && attachments.value.length === 0) return
  if (!activeConversation.value) {
    await startChat()
  }

  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  const text = input.value.trim() || 'Bitte analysiere den Anhang.'
  const media = [...attachments.value]
  input.value = ''
  clearAttachments()

  try {
    await sendPrompt(conversation.session.chatId, text, model, {
      media
    })
  } catch (err) {
    toast.add({
      title: 'Nachricht fehlgeschlagen',
      description: err instanceof Error ? err.message : 'Backend nicht erreichbar.',
      color: 'error'
    })
  }
}

const handleStop = () => {
  if (activeConversation.value) {
    stop(activeConversation.value.session.chatId)
  }
}

const handleReload = async () => {
  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  try {
    await retryLastPrompt(conversation.session.chatId, model)
  } catch (err) {
    toast.add({
      title: 'Wiederholen fehlgeschlagen',
      description: err instanceof Error ? err.message : 'Anfrage konnte nicht wiederholt werden.',
      color: 'error'
    })
  }
}

const startEditMessage = (messageId: string, currentText: string) => {
  editingMessageId.value = messageId
  editingText.value = currentText
}

const cancelEditMessage = () => {
  editingMessageId.value = null
  editingText.value = ''
}

const submitEditMessage = async () => {
  const msgId = editingMessageId.value
  const newText = editingText.value.trim()
  if (!msgId || !newText) return

  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  editingMessageId.value = null
  editingText.value = ''

  try {
    await editMessage(conversation.session.chatId, msgId, newText, model)
  } catch (err) {
    toast.add({
      title: 'Bearbeitung fehlgeschlagen',
      description: err instanceof Error ? err.message : 'Nachricht konnte nicht bearbeitet werden.',
      color: 'error'
    })
  }
}
</script>

<template>
  <div class="h-full">
  <UDashboardPanel class="h-full flex flex-col">
    <template #body>
      <UContainer class="py-4 space-y-4 flex-1 overflow-hidden flex flex-col">
        <UAlert
          v-if="errorMessage"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :description="errorMessage"
        />

        <div v-if="initializing" class="space-y-3">
          <div class="skeleton-shimmer h-14" />
          <div class="skeleton-shimmer h-24" />
          <div class="skeleton-shimmer h-14" />
        </div>

          <div v-else-if="!activeConversation">
          <div class="welcome-hero animate-fade-in-up">
            <h2>Chat Sribiddi</h2>
            <p class="mt-3 text-sm text-gray-500 dark:text-gray-400 max-w-md mx-auto">
              Wähle unten ein Modell und leg los. Dateien anhängen, im Web suchen und vieles mehr.
            </p>
            <div class="mt-6 flex flex-wrap justify-center gap-3">
              <button class="prompt-suggestion" aria-label="Quantencomputing erklären lassen" @click="input = 'Erkläre Quantencomputing in einfachen Worten'">
                💡 Quantencomputing erklären
              </button>
              <button class="prompt-suggestion" aria-label="Python-Funktion schreiben lassen" @click="input = 'Schreibe eine Python-Funktion, die eine Liste sortiert'">
                🐍 Python-Funktion schreiben
              </button>
              <button class="prompt-suggestion" aria-label="Aktuelle KI-Trends erfahren" @click="input = 'Welche aktuellen Trends gibt es im Bereich KI?'">
                🤖 Aktuelle KI-Trends
              </button>
            </div>
            <UButton color="primary" icon="i-heroicons-plus" class="mt-6 btn-interactive" @click="startChat()">
              Chat starten
            </UButton>
          </div>
        </div>

        <div v-else class="space-y-4 flex-1 min-h-0 flex flex-col">
          <div class="flex items-center justify-end gap-2">
            <UBadge
              v-if="selectedModel"
              color="primary"
              variant="soft"
              :label="selectedModel.label"
              :ui="{ rounded: 'rounded-full' }"
            />
          </div>

          <div class="flex-1 min-h-0">
            <UCard class="h-full card-elevated" :ui="{ body: 'h-full overflow-hidden' }">
              <div ref="messagesContainer" class="h-full overflow-y-auto scrollbar-thin">
                <UChatMessages
                  class="h-full"
                  :messages="activeConversation.messages"
                  :status="status"
                  :spacing-offset="96"
                  :user="{ side: 'right', variant: 'naked' }"
                  :assistant="{ side: 'left', variant: 'naked' }"
                >
                  <template #content="{ parts, message }: { parts: any, message: any }">
                    <template v-if="message?.role === 'assistant' && getReasoning(message.id)">
                      <ChatMessageReasoning
                        :text="getReasoning(message.id) || ''"
                        :expanded="!!reasoningExpanded[message.id]"
                        @toggle="reasoningExpanded[message.id] = !reasoningExpanded[message.id]"
                      />
                    </template>
                    <template v-if="message?.role === 'user' && message.id && editingMessageId === message.id">
                      <div class="space-y-2">
                        <textarea
                          v-model="editingText"
                          class="w-full min-h-[80px] rounded-xl bg-gray-100/80 dark:bg-white/[0.04] border border-gray-200 dark:border-white/[0.08] text-sm text-gray-800 dark:text-gray-100 px-3.5 py-2.5 resize-y focus:outline-none focus:ring-2 focus:ring-primary/40 transition-shadow"
                          @keydown.enter.ctrl="submitEditMessage"
                          @keydown.escape="cancelEditMessage"
                        />
                        <div class="flex items-center gap-2">
                          <UButton
                            size="xs"
                            color="primary"
                            icon="i-heroicons-check"
                            label="Speichern & neu generieren"
                            class="btn-interactive"
                            :disabled="!editingText.trim()"
                            @click="submitEditMessage"
                          />
                          <UButton
                            size="xs"
                            color="neutral"
                            variant="ghost"
                            icon="i-heroicons-x-mark"
                            label="Abbrechen"
                            @click="cancelEditMessage"
                          />
                        </div>
                      </div>
                    </template>
                      <template v-else>
                        <div class="group relative">
                          <div
                            v-if="showEditMessageButton(message)"
                            class="msg-actions absolute -left-7 top-0.5"
                          >
                          <UButton
                            size="xs"
                            color="neutral"
                            variant="ghost"
                            icon="i-heroicons-pencil-square"
                            :padded="false"
                            aria-label="Nachricht bearbeiten"
                            class="btn-interactive"
                              @click="startEditMessage(message.id, firstText(parts))"
                            />
                          </div>
                          <ToolCallDisplay
                            v-if="message?.role === 'assistant' && hasToolCalls(message.id)"
                            :tool-calls="getToolCalls(message.id)"
                            :loading="isToolCallLoading(message.id)"
                            :error="getToolCallError(message.id)"
                          />
                          <div :class="message?.role === 'user' ? 'msg-user' : message?.role === 'assistant' ? 'msg-assistant' : ''">
                            <MarkdownMessage
                              :text="firstText(parts)"
                              :class="message?.role === 'assistant' && status === 'streaming' && isLastMessage(message) ? 'streaming-cursor' : ''"
                            />
                          <div v-if="getFileParts(parts).length" class="flex flex-wrap gap-1.5 mt-2">
                            <UBadge
                              v-for="(file, idx) in getFileParts(parts)"
                              :key="idx"
                              variant="subtle"
                              color="neutral"
                              size="sm"
                            >
                              <UIcon :name="fileIcon(file.mediaType)" class="size-3.5 mr-1" />
                                {{ file.filename || formatMimeType(file.mediaType) }}
                              </UBadge>
                            </div>
                          </div>
                        </div>
                      <template v-if="message?.role === 'assistant'">
                        <!-- Typing indicator before first content -->
                        <div
                          v-if="status === 'streaming' && isLastMessage(message) && !firstText(parts)"
                          class="typing-dots"
                        >
                          <span /><span /><span />
                        </div>
                        <template v-for="usage in [getTokenUsage(message.id)]" :key="message.id">
                          <ChatMessageFooter
                            :usage="usage"
                            :cost="getMessageCost(message.id)"
                            :timestamp="message?.createdAt ? formatTimestamp(message.createdAt) : null"
                            :format-token-count="formatTokenCount"
                            :format-cost="formatCost"
                          />
                        </template>
                      </template>
                      <div
                        v-else-if="message?.createdAt && formatTimestamp(message.createdAt)"
                        class="mt-1.5 text-[11px] text-gray-400 dark:text-gray-500 select-none"
                      >
                        {{ formatTimestamp(message.createdAt) }}
                      </div>
                    </template>
                  </template>
                </UChatMessages>
              </div>
            </UCard>
          </div>
        </div>
      </UContainer>
    </template>

    <template #footer>
      <div class="relative glass-surface-light border-t border-gray-200/60 dark:border-white/[0.06]">

        <UContainer class="pb-4 sm:pb-6 space-y-2">
          <div class="flex items-center gap-2 flex-wrap">
            <input
              ref="fileInput"
              type="file"
              class="hidden"
              multiple
              @change="handleFiles"
            >
            <UButton
              size="xs"
              variant="ghost"
              color="neutral"
              icon="i-heroicons-paper-clip"
              label="Anhänge hinzufügen"
              class="btn-interactive"
              @click="fileInput?.click()"
            />
            <UButton
              v-if="attachments.length"
              size="xs"
              variant="ghost"
              color="neutral"
              icon="i-heroicons-x-mark"
              label="Leeren"
              class="btn-interactive"
              @click="clearAttachments"
            />
          </div>

          <div v-if="attachments.length" class="flex flex-wrap gap-2">
            <div
              v-for="(item, index) in attachments"
              :key="`${item.name || item.mimeType}-${index}`"
              class="animate-scale-in attachment-card"
            >
              <UIcon :name="fileIcon(item.mimeType)" class="size-4 text-gray-500" />
              <span class="truncate max-w-[150px]">{{ item.name || item.mimeType }}</span>
              <button
                class="inline-flex text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
                :aria-label="`Remove attachment ${item.name || item.mimeType}`"
                :title="`Remove attachment ${item.name || item.mimeType}`"
                @click="removeAttachment(index)"
              >
                <UIcon name="i-heroicons-x-mark" class="size-3.5" />
              </button>
            </div>
          </div>

          <UChatPrompt v-model="input" :error="promptError" @submit="handleSubmit">
            <template #header>
              <USelectMenu
                v-model="selectedModel"
                :items="sortedModels"
                class="w-56"
                :loading="modelsLoading"
                label-key="label"
                by="value"
                placeholder="Modell auswählen"
                variant="subtle"
                color="neutral"
              >
                <template #leading>
                  <UIcon name="i-heroicons-cpu-chip" />
                </template>
                <template #option="{ option }">
                  <div class="flex items-center justify-between gap-2 w-full">
                    <span class="truncate">{{ option.label }}</span>
                    <div class="flex items-center gap-1 flex-shrink-0">
                      <template v-for="priceLabel in [optionPriceLabel(option)]" :key="option.value">
                        <span
                          v-if="priceLabel"
                          class="text-[10px] text-emerald-600 dark:text-emerald-400"
                        >
                          {{ priceLabel }}
                        </span>
                      </template>
                      <UIcon
                        v-if="isFavorite(option)"
                        name="i-heroicons-star-solid"
                        class="size-3 text-yellow-400"
                      />
                      <UBadge :label="providerLabel(option.provider)" variant="soft" color="neutral" />
                    </div>
                  </div>
                </template>
              </USelectMenu>
              <UTooltip :text="webSearchEnabled ? 'Web-Suche aktiv' : 'Web-Suche deaktiviert'">
                <UButton
                  :icon="webSearchEnabled ? 'i-heroicons-globe-alt-solid' : 'i-heroicons-globe-alt'"
                  :color="webSearchEnabled ? 'primary' : 'neutral'"
                  :variant="webSearchEnabled ? 'soft' : 'ghost'"
                  size="sm"
                  :aria-label="webSearchEnabled ? 'Web-Suche deaktivieren' : 'Web-Suche aktivieren'"
                  @click="webSearchEnabled = !webSearchEnabled"
                />
              </UTooltip>
              <span
                v-if="modelPricingSummary"
                class="text-xs text-gray-500 dark:text-gray-400 inline-flex items-center gap-1"
              >
                <UIcon name="i-heroicons-banknotes" class="size-3 text-emerald-500" />
                {{ modelPricingSummary }}
              </span>
            </template>

            <div class="flex items-center gap-2">
              <UTooltip
                v-if="preSendCostEstimate"
                :content="{ side: 'top', align: 'end' }"
              >
                <button
                  type="button"
                  class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/10 focus:outline-none focus:ring-2 focus:ring-emerald-500/40"
                  :aria-label="`Geschätzte Kosten ${formatCost(preSendCostEstimate.totalCost)}`"
                >
                  <UIcon name="i-heroicons-banknotes" class="size-3" />
                  ~{{ formatCost(preSendCostEstimate.totalCost) }}
                </button>
                <template #content>
                  <div class="space-y-2 text-xs">
                    <div class="font-medium text-gray-900 dark:text-gray-100">
                      Geschätzte Anfragekosten
                    </div>
                    <div class="grid grid-cols-[auto_auto] gap-x-3 gap-y-1 whitespace-nowrap">
                      <span class="text-gray-500 dark:text-gray-400">Eingabe-Tokens</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ formatTokenCount(preSendCostEstimate.inputTokens) }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">Geschätzte Ausgabe</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ formatTokenCount(preSendCostEstimate.estimatedOutputTokens) }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">Preis Eingabe / 1M</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ preSendPromptRateLabel || '—' }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">Preis Ausgabe / 1M</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ preSendCompletionRateLabel || '—' }}
                      </span>
                    </div>
                  </div>
                </template>
              </UTooltip>

              <UChatPromptSubmit
                :status="status"
                @stop="handleStop"
                @reload="handleReload"
              />
            </div>
          </UChatPrompt>
        </UContainer>
      </div>
    </template>
  </UDashboardPanel>

  <Transition name="drag-overlay">
    <div
      v-if="isDragOver"
      class="fixed inset-0 z-50 flex items-center justify-center pointer-events-none bg-primary/10 backdrop-blur-md"
    >
      <div class="animate-scale-in flex flex-col items-center gap-3 text-primary glass-surface rounded-2xl px-10 py-8 ring-2 ring-primary/50 ring-inset shadow-2xl">
        <UIcon name="i-heroicons-arrow-down-tray" class="size-12" />
        <span class="text-lg font-semibold tracking-tight">Dateien zum Anhängen loslassen</span>
      </div>
    </div>
  </Transition>
  </div>
</template>

<style scoped>
.drag-overlay-enter-active,
.drag-overlay-leave-active {
  transition: opacity 0.2s ease;
}
.drag-overlay-enter-from,
.drag-overlay-leave-to {
  opacity: 0;
}
</style>
