<script setup lang="ts">
import type { ModelOption } from '~/composables/useChatApi'
import { isModelLocked } from '~/composables/useChatApi'
import { calculateMessageCost, estimatePreSendCost, formatCost, formatRatePerMillion } from '~/composables/chat-helpers'

const { t } = useI18n()
const tt = useTranslatedError()

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
  isGuestMode,
  setModelForChat,
  sortedModels,
  startChat,
  stop,
  tokenUsageMap,
  webSearchEnabled
} = useChatStore()
const { checkAuth } = useAuth()
const { settings } = useUserSettings()
const { exportRunbook, uploadDocument } = useChatApi()

const input = ref('')
const initializing = ref(true)
const exportingRunbook = ref(false)
const toast = useToast()
const {
  attachments,
  documentAttachments,
  fileInput,
  isDragOver,
  clearAttachments,
  removeAttachment,
  removeDocumentAttachment,
  handleFiles
} = useFileAttachments({
  fileReadFailedMessage: name => t('chat.fileReadFailed', { name }),
  onUploadFailed: reason => {
    toast.add({
      title: t('chat.uploadFailedTitle'),
      description: tt(reason, 'chat.attachmentReadFallback'),
      color: 'error'
    })
  }
})
const {
  messagesContainer,
  scrollToBottom,
  skipNextSmooth,
  scrollAfterMessageLengthChange,
  scheduleStreamingScroll
} = useChatScroll()

const selectedModel = computed<ModelOption | null>({
  get: () => {
    const conversation = activeConversation.value
    if (conversation?.model) return conversation.model
    return defaultModel.value
  },
  set: value => {
    if (value && isModelLocked(value)) {
      // Do not assign the locked model — route the user to the upgrade page
      // instead (see FE-5). Keep the current selection untouched.
      void navigateTo('/pricing')
      return
    }
    if (value && activeConversation.value) {
      setModelForChat(activeConversation.value.session.chatId, value)
      saveLastUsedModel(value)
    }
  }
})

const {
  editingMessageId,
  editingText,
  startEditMessage,
  cancelEditMessage,
  submitEditMessage
} = useMessageEditing({
  activeConversation,
  selectedModel,
  editMessage,
  onEditFailed: err => {
    toast.add({
      title: t('chat.editFailedTitle'),
      description: tt(err, 'chat.editFailedDescription'),
      color: 'error'
    })
  }
})

const status = computed(() => activeConversation.value?.status || 'ready')
const translatedPromptError = computed(() => {
  const err = activeConversation.value?.error
  return err ? tt(new Error(err)) : null
})
const translatedModelsError = computed(() =>
  modelsError.value ? tt(new Error(modelsError.value)) : null
)
const promptError = computed(() =>
  translatedPromptError.value ? new Error(translatedPromptError.value) : undefined
)

type ChatMessagePart = { type: string; text?: string; mediaType?: string; filename?: string }
type ChatContentSlot = {
  parts: ChatMessagePart[]
  message: { role?: string; id?: string; createdAt?: string | Date }
}

// Up-sell banner (FE-6): shown when the backend rejects a send/stream request
// with a DAILY_CAP_EXCEEDED reason (HTTP 402, see GlobalExceptionHandler). The
// JSON body from the backend is surfaced verbatim in `conversation.error`, so
// we look for the reason token substring — this keeps the trigger stable even
// if the localized `message` text changes.
const upgradeBannerDismissed = ref(false)
function planRestrictionReason(errorText: string | null | undefined): string | null {
  if (!errorText) return null
  try {
    const parsed = JSON.parse(errorText) as { reason?: unknown }
    if (typeof parsed.reason === 'string') return parsed.reason
  } catch {
    // fall through — match reason token in plain text
  }
  const upper = errorText.toUpperCase()
  if (upper.includes('DAILY_CAP_EXCEEDED')) return 'DAILY_CAP_EXCEEDED'
  if (upper.includes('MODEL_NOT_ALLOWED')) return 'MODEL_NOT_ALLOWED'
  if (upper.includes('PARALLEL_STREAMS_EXCEEDED')) return 'PARALLEL_STREAMS_EXCEEDED'
  return null
}

const showUpgradeBanner = computed(() => {
  if (upgradeBannerDismissed.value) return false
  return planRestrictionReason(activeConversation.value?.error) === 'DAILY_CAP_EXCEEDED'
})

// Reset dismissal whenever the active chat changes or a new error is surfaced
// so the banner reappears after subsequent quota-hit attempts.
watch(
  () => [activeChatId.value, activeConversation.value?.error] as const,
  ([chatId, err], [prevChatId, prevErr]) => {
    if (chatId !== prevChatId || err !== prevErr) {
      upgradeBannerDismissed.value = false
    }
  }
)

const dismissUpgradeBanner = () => {
  upgradeBannerDismissed.value = true
}

const goToPricing = () => {
  void navigateTo('/pricing')
}

const firstText = (parts?: ChatMessagePart[]) =>
  parts?.find(part => part.type === 'text' && typeof part.text === 'string')?.text || ''

type FilePart = { type: 'file'; mediaType: string; filename?: string }

const getFileParts = (parts?: ChatMessagePart[]): FilePart[] =>
  (parts || []).filter((p): p is FilePart => p.type === 'file' && typeof p.mediaType === 'string')

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

const copiedMessageId = ref<string | null>(null)

const copyMessage = async (messageId: string | undefined, text: string) => {
  if (!messageId || !text) return
  try {
    await navigator.clipboard.writeText(text)
    copiedMessageId.value = messageId
    setTimeout(() => {
      copiedMessageId.value = null
    }, 2000)
  } catch (err) {
    console.error('Failed to copy message', err)
  }
}

const modelPricingSummary = computed(() => {
  const model = selectedModel.value
  const p = formatRatePerMillion(model?.promptPrice)
  const c = formatRatePerMillion(model?.completionPrice)
  if (!p && !c) return null

  if (p != null && c != null) {
    return t('chat.pricingPerMillionInOut', { input: p, output: c })
  }
  return t('chat.pricingPerMillion', { prompt: p ?? c })
})

const optionPriceLabel = (option: ModelOption): string | null =>
  formatRatePerMillion(option.promptPrice)

const pendingAttachmentCount = computed(() => attachments.value.length + documentAttachments.value.length)

const pendingPromptText = computed(() => {
  const trimmed = input.value.trim()
  if (trimmed) return trimmed
  return pendingAttachmentCount.value ? t('chat.analyzeAttachment') : ''
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

const hasPendingPrompt = computed(() => input.value.trim().length > 0 || pendingAttachmentCount.value > 0)
const canSubmitPrompt = computed(() =>
  status.value === 'ready' && !!selectedModel.value && hasPendingPrompt.value
)
const chatReady = computed(() => !initializing.value && !!activeConversation.value)

onMounted(async () => {
  try {
    await checkAuth()
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
      title: t('chat.startFailedTitle'),
      description: tt(err, 'chat.startFailedDescription'),
      color: 'error'
    })
  } finally {
    initializing.value = false
  }
})

watch(activeChatId, async newId => {
  if (!newId) return
  const conversation = conversations.value.find(c => c.session.chatId === newId)
  if (!conversation) return

  if (!conversation.model && defaultModel.value) {
    setModelForChat(conversation.session.chatId, defaultModel.value)
  }

  if (conversation.messages.length === 0) {
    skipNextSmooth()
    await loadMessagesForChat(newId)
  }
  scrollToBottom('auto')
})

// Scroll smoothly when a new message is added to the conversation
watch(
  () => activeConversation.value?.messages?.length,
  newLength => scrollAfterMessageLengthChange(newLength),
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
    const oldSnapshotsById = new Map(oldSnapshots?.map(snapshot => [snapshot.id, snapshot]) ?? [])

    for (const snapshot of newSnapshots) {
      if (snapshot.role !== 'assistant') continue
      if (!snapshot.hasTools) continue
      if (snapshot.toolCount !== 0) continue
      if (isToolCallLoading(snapshot.id)) continue

      const prev = oldSnapshotsById.get(snapshot.id)
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
  newText => scheduleStreamingScroll(newText),
)

const welcomeSuggestions = computed(() => [
  {
    label: t('chat.suggestionQuantum'),
    prompt: t('chat.suggestionQuantumPrompt'),
    aria: t('chat.suggestionQuantumAria')
  },
  {
    label: t('chat.suggestionPython'),
    prompt: t('chat.suggestionPythonPrompt'),
    aria: t('chat.suggestionPythonAria')
  },
  {
    label: t('chat.suggestionAi'),
    prompt: t('chat.suggestionAiPrompt'),
    aria: t('chat.suggestionAiAria')
  }
])

const applyAndSubmit = (prompt: string) => {
  input.value = prompt
  handleSubmit()
}

async function handleSubmit() {
  if (status.value !== 'ready') return
  if (!input.value.trim() && pendingAttachmentCount.value === 0) return
  if (!activeConversation.value) {
    await startChat()
  }

  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return
  if (conversation.local && documentAttachments.value.length > 0) {
    toast.add({
      title: t('chat.uploadFailedTitle'),
      description: t('chat.documentUploadRequiresLogin'),
      color: 'error'
    })
    return
  }

  const text = input.value.trim() || t('chat.analyzeAttachment')
  const media = [...attachments.value]
  const documents = [...documentAttachments.value]
  const savedInput = input.value
  input.value = ''
  clearAttachments()

  try {
    for (const document of documents) {
      await uploadDocument(conversation.session.chatId, document.file)
    }
    await sendPrompt(conversation.session.chatId, text, model, {
      media
    })
  } catch (err) {
    input.value = savedInput
    toast.add({
      title: t('chat.messageFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  }
}

const handleStop = () => {
  if (activeConversation.value) {
    stop(activeConversation.value.session.chatId)
  }
}

const downloadRunbook = async () => {
  const conversation = activeConversation.value
  if (!conversation || conversation.local || exportingRunbook.value) return

  exportingRunbook.value = true
  try {
    const result = await exportRunbook(conversation.session.chatId)
    const blob = new Blob([result.markdown], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename || t('chat.runbookFallbackFilename')
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    toast.add({ title: t('chat.runbookExportedTitle'), color: 'success' })
  } catch (err) {
    toast.add({
      title: t('chat.runbookExportFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    exportingRunbook.value = false
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
      title: t('chat.retryFailedTitle'),
      description: tt(err, 'chat.retryFailedDescription'),
      color: 'error'
    })
  }
}
</script>

<template>
  <div
    class="h-full min-h-0 flex flex-col"
    data-testid="chat-page"
    :data-ready="chatReady ? 'true' : 'false'"
  >
  <UDashboardPanel class="h-full min-h-0 flex flex-col">
    <template #body>
      <UContainer class="py-4 space-y-4 flex-1 min-h-0 overflow-hidden flex flex-col">
        <UAlert
          v-if="showUpgradeBanner"
          color="primary"
          variant="subtle"
          icon="i-heroicons-sparkles"
          :title="t('billing.upgradeBanner.title')"
          :description="t('billing.upgradeBanner.description')"
          :close="{ 'aria-label': t('billing.upgradeBanner.dismiss') }"
          :actions="[
            {
              label: t('billing.upgradeBanner.cta'),
              color: 'primary',
              variant: 'solid',
              onClick: goToPricing
            }
          ]"
          @close="dismissUpgradeBanner"
        />
        <UAlert
          v-if="translatedModelsError"
          color="warning"
          icon="i-heroicons-exclamation-triangle"
          :description="translatedModelsError"
        />
        <UAlert
          v-if="translatedPromptError"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :description="translatedPromptError"
        />

        <div v-if="initializing" class="space-y-3">
          <div class="skeleton-shimmer h-14" />
          <div class="skeleton-shimmer h-24" />
          <div class="skeleton-shimmer h-14" />
        </div>

          <div v-else-if="!activeConversation">
            <ChatWelcomeHero
              :suggestions="welcomeSuggestions"
              @pick="applyAndSubmit"
              @start="startChat()"
            />
          </div>

        <div v-else class="space-y-4 flex-1 min-h-0 flex flex-col">
          <div class="flex-1 min-h-0">
            <UCard class="h-full" :ui="{ root: 'bg-transparent ring-0 divide-y-0', body: 'h-full overflow-hidden' }">
              <div ref="messagesContainer" class="h-full overflow-y-auto scrollbar-thin">
                <UChatMessages
                  class="h-full"
                  :messages="activeConversation.messages"
                  :status="status"
                  :spacing-offset="96"
                  :user="{ side: 'right', variant: 'naked' }"
                  :assistant="{ side: 'left', variant: 'naked' }"
                >
                  <template #content="{ parts, message }: ChatContentSlot">
                    <template v-if="message?.role === 'assistant' && getReasoning(message.id)">
                      <ChatMessageReasoning
                        :text="getReasoning(message.id) || ''"
                        :expanded="!!reasoningExpanded[message.id]"
                        @toggle="reasoningExpanded[message.id] = !reasoningExpanded[message.id]"
                      />
                    </template>
                    <template v-if="message?.role === 'user' && message.id && editingMessageId === message.id">
                      <ChatMessageEditor
                        v-model="editingText"
                        @submit="submitEditMessage"
                        @cancel="cancelEditMessage"
                      />
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
                            :aria-label="t('chat.editMessage')"
                            class="btn-interactive"
                              @click="startEditMessage(message.id, firstText(parts))"
                            />
                          </div>
                          <ToolCallDisplay
                            v-if="!isGuestMode && message?.role === 'assistant' && hasToolCalls(message.id)"
                            :tool-calls="getToolCalls(message.id)"
                            :loading="isToolCallLoading(message.id)"
                            :error="getToolCallError(message.id)"
                          />
                          <div :class="message?.role === 'user' ? 'msg-user' : message?.role === 'assistant' ? 'msg-assistant' : ''">
                            <MarkdownMessage
                              :text="firstText(parts)"
                              :streaming="message?.role === 'assistant' && status === 'streaming' && isLastMessage(message)"
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
                        <ChatMessageFooter
                          :usage="getTokenUsage(message.id)"
                          :cost="getMessageCost(message.id)"
                          :timestamp="message?.createdAt ? formatTimestamp(message.createdAt) : null"
                          :format-token-count="formatTokenCount"
                          :format-cost="formatCost"
                        />
                        <div
                          v-if="isLastMessage(message) && status !== 'streaming' && firstText(parts)"
                          class="flex items-center gap-1.5 mt-2"
                        >
                          <UTooltip :text="copiedMessageId === message.id ? t('chat.copiedResponse') : t('chat.copyResponse')">
                            <UButton
                              :icon="copiedMessageId === message.id ? 'i-heroicons-check' : 'i-heroicons-clipboard-document'"
                              color="neutral"
                              variant="ghost"
                              size="xs"
                              :padded="false"
                              :aria-label="t('chat.copyResponse')"
                              class="btn-interactive"
                              @click="copyMessage(message.id, firstText(parts))"
                            />
                          </UTooltip>
                          <UTooltip v-if="!activeConversation.local" :text="t('chat.exportRunbook')">
                            <UButton
                              icon="i-heroicons-arrow-down-tray"
                              color="neutral"
                              variant="ghost"
                              size="xs"
                              :padded="false"
                              :loading="exportingRunbook"
                              :aria-label="t('chat.exportRunbook')"
                              class="btn-interactive"
                              @click="downloadRunbook"
                            />
                          </UTooltip>
                        </div>
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
      <div class="relative">

        <UContainer class="pb-4 sm:pb-6 space-y-2">
          <input
            ref="fileInput"
            type="file"
            class="hidden"
            multiple
            @change="handleFiles"
          >

          <div v-if="pendingAttachmentCount" class="flex flex-wrap gap-2">
            <div
              v-for="(item, index) in attachments"
              :key="`${item.name || item.mimeType}-${index}`"
              class="animate-scale-in attachment-card"
            >
              <UIcon :name="fileIcon(item.mimeType)" class="size-4 text-gray-500" />
              <span class="truncate max-w-[150px]">{{ item.name || item.mimeType }}</span>
              <button
                class="inline-flex text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
                :aria-label="t('chat.removeAttachment', { name: item.name || item.mimeType })"
                :title="t('chat.removeAttachment', { name: item.name || item.mimeType })"
                @click="removeAttachment(index)"
              >
                <UIcon name="i-heroicons-x-mark" class="size-3.5" />
              </button>
            </div>
            <div
              v-for="(item, index) in documentAttachments"
              :key="`document-${item.name || item.mimeType}-${index}`"
              class="animate-scale-in attachment-card"
            >
              <UIcon :name="fileIcon(item.mimeType)" class="size-4 text-gray-500" />
              <span class="truncate max-w-[150px]">{{ item.name || item.mimeType }}</span>
              <button
                class="inline-flex text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
                :aria-label="t('chat.removeAttachment', { name: item.name || item.mimeType })"
                :title="t('chat.removeAttachment', { name: item.name || item.mimeType })"
                @click="removeDocumentAttachment(index)"
              >
                <UIcon name="i-heroicons-x-mark" class="size-3.5" />
              </button>
            </div>
          </div>

          <UChatPrompt
            v-if="chatReady"
            v-model="input"
            :error="promptError"
            @submit="handleSubmit"
          >
            <template #header>
              <UTooltip :text="t('chat.addAttachments')">
                <UButton
                  icon="i-heroicons-paper-clip"
                  color="neutral"
                  variant="ghost"
                  size="sm"
                  :aria-label="t('chat.addAttachments')"
                  class="btn-interactive"
                  @click="fileInput?.click()"
                />
              </UTooltip>
              <UButton
                v-if="pendingAttachmentCount"
                icon="i-heroicons-x-mark"
                color="neutral"
                variant="ghost"
                size="sm"
                :aria-label="t('chat.clearAttachments')"
                class="btn-interactive"
                @click="clearAttachments"
              />
              <USelectMenu
                v-model="selectedModel"
                :items="sortedModels"
                class="w-56"
                :loading="modelsLoading"
                label-key="label"
                by="value"
                :placeholder="t('chat.modelPlaceholder')"
                variant="subtle"
                color="neutral"
              >
                <template #leading>
                  <UIcon name="i-heroicons-cpu-chip" />
                </template>
                <template #option="{ option }">
                  <div
                    class="flex items-center justify-between gap-2 w-full"
                    :class="{ 'opacity-60': isModelLocked(option) }"
                  >
                    <span class="truncate">{{ option.label }}</span>
                    <div class="flex items-center gap-1 flex-shrink-0">
                      <span
                        v-if="optionPriceLabel(option)"
                        class="text-[10px] text-emerald-600 dark:text-emerald-400"
                      >
                        {{ optionPriceLabel(option) }}
                      </span>
                      <UIcon
                        v-if="isFavorite(option)"
                        name="i-heroicons-star-solid"
                        class="size-3 text-yellow-400"
                      />
                      <UTooltip v-if="isModelLocked(option)" :text="t('models.premiumRequired')">
                        <UIcon
                          name="i-heroicons-lock-closed"
                          class="size-3.5 text-gray-500 dark:text-gray-400"
                          :aria-label="t('models.premiumRequired')"
                        />
                      </UTooltip>
                      <UBadge :label="providerLabel(option.provider)" variant="soft" color="neutral" />
                    </div>
                  </div>
                </template>
              </USelectMenu>
              <UTooltip
                v-if="!isGuestMode"
                :text="webSearchEnabled ? t('chat.webSearchDisable') : t('chat.webSearchEnable')"
              >
                <UButton
                  :icon="webSearchEnabled ? 'i-heroicons-globe-alt-solid' : 'i-heroicons-globe-alt'"
                  :color="webSearchEnabled ? 'primary' : 'neutral'"
                  :variant="webSearchEnabled ? 'soft' : 'outline'"
                  size="md"
                  label="Web"
                  :aria-pressed="webSearchEnabled"
                  :aria-label="webSearchEnabled ? t('chat.webSearchDisable') : t('chat.webSearchEnable')"
                  class="btn-interactive web-toggle"
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
                  :aria-label="t('chat.estimatedCostAria', { amount: formatCost(preSendCostEstimate.totalCost) })"
                >
                  <UIcon name="i-heroicons-banknotes" class="size-3" />
                  ~{{ formatCost(preSendCostEstimate.totalCost) }}
                </button>
                <template #content>
                  <div class="space-y-2 text-xs">
                    <div class="font-medium text-gray-900 dark:text-gray-100">
                      {{ t('chat.estimatedRequestCost') }}
                    </div>
                    <div class="grid grid-cols-[auto_auto] gap-x-3 gap-y-1 whitespace-nowrap">
                      <span class="text-gray-500 dark:text-gray-400">{{ t('chat.inputTokens') }}</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ formatTokenCount(preSendCostEstimate.inputTokens) }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">{{ t('chat.estimatedOutput') }}</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ formatTokenCount(preSendCostEstimate.estimatedOutputTokens) }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">{{ t('chat.priceInputPerMillion') }}</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ preSendPromptRateLabel || '—' }}
                      </span>
                      <span class="text-gray-500 dark:text-gray-400">{{ t('chat.priceOutputPerMillion') }}</span>
                      <span class="text-right text-gray-900 dark:text-gray-100">
                        {{ preSendCompletionRateLabel || '—' }}
                      </span>
                    </div>
                  </div>
                </template>
              </UTooltip>

              <UButton
                v-if="status === 'ready'"
                type="button"
                icon="i-lucide-arrow-up"
                color="primary"
                :disabled="!canSubmitPrompt"
                :aria-label="t('chat.sendMessage')"
                data-testid="chat-submit"
                @click="handleSubmit"
              />
              <UButton
                v-else-if="status === 'submitted' || status === 'streaming'"
                type="button"
                icon="i-lucide-square"
                color="neutral"
                variant="subtle"
                :aria-label="t('chat.stopGenerating')"
                data-testid="chat-stop"
                @click="handleStop"
              />
              <UButton
                v-else
                type="button"
                icon="i-heroicons-arrow-path"
                color="error"
                variant="soft"
                :aria-label="t('common.reload')"
                data-testid="chat-reload"
                @click="handleReload"
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
        <span class="text-lg font-semibold tracking-tight">{{ t('chat.dropToAttach') }}</span>
      </div>
    </div>
  </Transition>
  </div>
</template>

<style scoped>
/* Larger Web toggle inside the prompt header */
.web-toggle {
  font-weight: 500;
}

.drag-overlay-enter-active,
.drag-overlay-leave-active {
  transition: opacity 0.2s ease;
}
.drag-overlay-enter-from,
.drag-overlay-leave-to {
  opacity: 0;
}
</style>
