<script setup lang="ts">
import type { MediaAttachment, ModelOption } from '~/composables/useChatApi'

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
  models,
  modelsError,
  modelsLoading,
  reasoningMap,
  retryLastPrompt,
  saveLastUsedModel,
  sendPrompt,
  setModelForChat,
  sortedModels,
  startChat,
  stop,
  tokenUsageMap,
  webSearchEnabled
} = useChatStore()

const input = ref('')
const attachments = ref<MediaAttachment[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const initializing = ref(true)
const toast = useToast()

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

const getReasoning = (messageId: string) => reasoningMap.value[messageId] ?? null

/**
 * Returns true if a UI message id corresponds to a persisted backend message
 * (i.e. has the "msg-{numericId}" format with a digits-only suffix).
 */
const isPersistedMessage = (id: string | undefined) =>
  typeof id === 'string' && /^msg-\d+$/.test(id)

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

const toDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(new Error(`Unable to read ${file.name}`))
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
        title: 'Upload failed',
        description: result.reason instanceof Error ? result.reason.message : 'Could not read attachment.',
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
  const target = event.currentTarget as HTMLElement
  if (!event.relatedTarget || !target.contains(event.relatedTarget as Node)) {
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
      title: 'Chat could not start',
      description: err instanceof Error ? err.message : 'Check if the backend is running.',
      color: 'error'
    })
  } finally {
    initializing.value = false
  }
})

onUnmounted(() => {
  document.removeEventListener('paste', handlePaste)
})

watch(activeChatId, async newId => {
  if (!newId) return
  const conversation = conversations.value.find(c => c.session.chatId === newId)
  if (!conversation) return

  if (!conversation.model && defaultModel.value) {
    setModelForChat(conversation.session.chatId, defaultModel.value)
  }

  if (conversation.messages.length === 0) {
    await loadMessagesForChat(newId)
  }
})

async function handleSubmit() {
  if (!input.value.trim() && attachments.value.length === 0) return
  if (!activeConversation.value) {
    await startChat()
  }

  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  const text = input.value.trim() || 'Please analyze the attachment.'
  const media = [...attachments.value]
  input.value = ''
  clearAttachments()

  try {
    await sendPrompt(conversation.session.chatId, text, model, {
      media
    })
  } catch (err) {
    toast.add({
      title: 'Message failed',
      description: err instanceof Error ? err.message : 'Unable to reach backend.',
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
      title: 'Retry failed',
      description: err instanceof Error ? err.message : 'Unable to retry request.',
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
      title: 'Edit failed',
      description: err instanceof Error ? err.message : 'Unable to edit message.',
      color: 'error'
    })
  }
}
</script>

<template>
  <UDashboardPanel class="h-full flex flex-col">
    <template #header>
      <UDashboardNavbar
        title="Chat"
        :badge="modelsLoading ? 'Loading models…' : undefined"
      />
    </template>

    <template #body>
      <UContainer class="py-4 space-y-4 flex-1 overflow-hidden flex flex-col">
        <UAlert
          v-if="errorMessage"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :description="errorMessage"
        />

        <div v-if="initializing" class="space-y-3">
          <USkeleton class="h-14" />
          <USkeleton class="h-24" />
          <USkeleton class="h-14" />
        </div>

        <div v-else-if="!activeConversation">
          <UEmptyState
            icon="i-heroicons-chat-bubble-left-right"
            title="No chat yet"
            description="Start a new chat to talk to the backend models."
          >
            <UButton color="primary" icon="i-heroicons-plus" @click="startChat()">
              Start chat
            </UButton>
          </UEmptyState>
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
            <UCard class="h-full" :ui="{ body: 'h-full overflow-hidden' }">
              <div class="h-full overflow-y-auto pr-1">
                <UChatMessages
                  class="h-full"
                  :messages="activeConversation.messages"
                  :status="status"
                  :should-scroll-to-bottom="true"
                  :auto-scroll="{ size: 'sm', color: 'neutral', variant: 'outline' }"
                  :spacing-offset="96"
                >
                  <template #content="{ parts, message }">
                    <template v-if="message?.role === 'assistant' && getReasoning(message.id)">
                      <div class="mb-3 rounded-lg border border-gray-700/50 bg-gray-900/50 overflow-hidden">
                        <button
                          class="w-full flex items-center gap-2 px-3 py-2 text-xs text-gray-400 hover:text-gray-300 transition-colors"
                          @click="reasoningExpanded[message.id] = !reasoningExpanded[message.id]"
                        >
                          <UIcon
                            name="i-heroicons-light-bulb"
                            class="size-3.5 text-amber-400/70"
                          />
                          <span class="font-medium">Thinking</span>
                          <UIcon
                            :name="reasoningExpanded[message.id] ? 'i-heroicons-chevron-up' : 'i-heroicons-chevron-down'"
                            class="size-3 ml-auto"
                          />
                        </button>
                        <div
                          v-if="reasoningExpanded[message.id]"
                          class="px-3 pb-3 text-sm text-gray-400 whitespace-pre-wrap border-t border-gray-700/30"
                        >
                          {{ getReasoning(message.id) }}
                        </div>
                      </div>
                    </template>
                    <template v-if="message?.role === 'user' && message.id && editingMessageId === message.id">
                      <div class="space-y-2">
                        <textarea
                          v-model="editingText"
                          class="w-full min-h-[80px] rounded-lg bg-gray-800/60 border border-gray-600 text-sm text-gray-100 px-3 py-2 resize-y focus:outline-none focus:ring-1 focus:ring-sky-500"
                          @keydown.enter.ctrl="submitEditMessage"
                          @keydown.escape="cancelEditMessage"
                        />
                        <div class="flex items-center gap-2">
                          <UButton
                            size="xs"
                            color="primary"
                            icon="i-heroicons-check"
                            label="Save & regenerate"
                            :disabled="!editingText.trim()"
                            @click="submitEditMessage"
                          />
                          <UButton
                            size="xs"
                            color="neutral"
                            variant="ghost"
                            icon="i-heroicons-x-mark"
                            label="Cancel"
                            @click="cancelEditMessage"
                          />
                        </div>
                      </div>
                    </template>
                    <template v-else>
                      <div class="group relative">
                        <MarkdownMessage :text="firstText(parts)" />
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
                        <template v-if="message?.role === 'user' && isPersistedMessage(message.id) && !getFileParts(parts).length && status === 'ready'">
                          <UButton
                            class="absolute -top-1 -right-1 opacity-0 group-hover:opacity-100 transition-opacity"
                            size="xs"
                            color="neutral"
                            variant="ghost"
                            icon="i-heroicons-pencil-square"
                            :padded="false"
                            @click="startEditMessage(message.id, firstText(parts))"
                          />
                        </template>
                      </div>
                      <template v-if="message?.role === 'assistant'">
                        <template v-for="usage in [getTokenUsage(message.id)]" :key="message.id">
                          <div
                            v-if="usage"
                            class="flex items-center gap-3 mt-2 text-xs text-gray-500 dark:text-gray-400"
                          >
                            <span v-if="usage.promptTokens != null" class="inline-flex items-center gap-1">
                              <UIcon name="i-heroicons-arrow-up-tray" class="size-3" />
                              {{ formatTokenCount(usage.promptTokens) }}
                            </span>
                            <span v-if="usage.generationTokens != null" class="inline-flex items-center gap-1">
                              <UIcon name="i-heroicons-arrow-down-tray" class="size-3" />
                              {{ formatTokenCount(usage.generationTokens) }}
                            </span>
                            <span v-if="usage.totalTokens != null" class="inline-flex items-center gap-1">
                              <UIcon name="i-heroicons-calculator" class="size-3" />
                              {{ formatTokenCount(usage.totalTokens) }} tokens
                            </span>
                          </div>
                        </template>
                      </template>
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
      <div
        class="relative transition-colors"
        :class="isDragOver ? 'ring-2 ring-primary ring-inset bg-primary/5' : ''"
        @dragover="handleDragOver"
        @dragleave="handleDragLeave"
        @drop="handleDrop"
      >
        <div
          v-if="isDragOver"
          class="absolute inset-0 flex items-center justify-center z-10 pointer-events-none"
        >
          <div class="flex flex-col items-center gap-2 text-primary">
            <UIcon name="i-heroicons-arrow-down-tray" class="size-8" />
            <span class="text-sm font-medium">Drop files to attach</span>
          </div>
        </div>

        <UContainer class="pb-4 sm:pb-6 space-y-2">
          <div class="flex items-center gap-2 flex-wrap">
            <label class="inline-flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300">
              <UIcon name="i-heroicons-paper-clip" />
              <span>Add attachments</span>
              <input
                ref="fileInput"
                type="file"
                class="hidden"
                multiple
                @change="handleFiles"
              >
            </label>
            <UButton
              size="xs"
              variant="ghost"
              color="neutral"
              icon="i-heroicons-paper-clip"
              label="Choose files"
              @click="fileInput?.click()"
            />
            <UButton
              v-if="attachments.length"
              size="xs"
              variant="ghost"
              color="neutral"
              icon="i-heroicons-x-mark"
              label="Clear"
              @click="clearAttachments"
            />
          </div>

          <div v-if="attachments.length" class="flex flex-wrap gap-2">
            <UBadge
              v-for="(item, index) in attachments"
              :key="`${item.name || item.mimeType}-${index}`"
              variant="soft"
              color="neutral"
            >
              <span class="mr-1">{{ item.name || item.mimeType }}</span>
              <button class="inline-flex" @click="removeAttachment(index)">
                <UIcon name="i-heroicons-x-mark" class="size-3" />
              </button>
            </UBadge>
          </div>

          <UChatPrompt v-model="input" :error="errorMessage" @submit="handleSubmit">
            <template #header>
              <USelectMenu
                v-model="selectedModel"
                :items="sortedModels"
                class="w-56"
                :loading="modelsLoading"
                label-key="label"
                by="value"
                placeholder="Pick model"
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
              <UTooltip :text="webSearchEnabled ? 'Web search enabled' : 'Web search disabled'">
                <UButton
                  :icon="webSearchEnabled ? 'i-heroicons-globe-alt-solid' : 'i-heroicons-globe-alt'"
                  :color="webSearchEnabled ? 'primary' : 'neutral'"
                  :variant="webSearchEnabled ? 'soft' : 'ghost'"
                  size="sm"
                  @click="webSearchEnabled = !webSearchEnabled"
                />
              </UTooltip>
            </template>

            <UChatPromptSubmit
              :status="status"
              @stop="handleStop"
              @reload="handleReload"
            />
          </UChatPrompt>
        </UContainer>
      </div>
    </template>
  </UDashboardPanel>
</template>
