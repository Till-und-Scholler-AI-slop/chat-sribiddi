import type { ChatStatus, UIMessage } from 'ai'
import type { BackendMessage, ChatSession, MediaAttachment, ModelOption, SendMessageResponse, StreamEvent, TokenUsage } from '~/composables/useChatApi'
import { PROVIDERS, useChatApi } from '~/composables/useChatApi'
import { useUserSettings } from '~/composables/useUserSettings'

export const favoriteModelKey = (model: ModelOption) => `${model.provider}:${model.value}`

type Conversation = {
  session: ChatSession
  messages: UIMessage[]
  status: ChatStatus
  error: string | null
  activeRequest?: AbortController
  model?: ModelOption | null
  lastPrompt: string | null
}

const messageId = () =>
  globalThis.crypto?.randomUUID?.() ||
  `msg-${Date.now()}-${Math.random().toString(16).slice(2)}`

export function extractAssistantText(response: any): string {
  const pick = (candidate: any): string | null => {
    if (!candidate) return null
    if (typeof candidate === 'string') return candidate
    if (typeof candidate.text === 'string') return candidate.text
    if (typeof candidate.content === 'string') return candidate.content

    if (Array.isArray(candidate)) {
      for (const item of candidate) {
        const found = pick(item)
        if (found) return found
      }
      return null
    }

    if (Array.isArray(candidate.content)) {
      const found = pick(candidate.content)
      if (found) return found
    }

    if (candidate.output) {
      const found = pick(candidate.output)
      if (found) return found
    }

    if (candidate.message) {
      const found = pick(candidate.message)
      if (found) return found
    }

    if (candidate.result) {
      const found = pick(candidate.result)
      if (found) return found
    }

    if (Array.isArray(candidate.results)) {
      const found = pick(candidate.results)
      if (found) return found
    }

    return null
  }

  return pick(response)?.trim() || 'No response received.'
}

export function parseAttachmentContext(context: string): string[] {
  const prefix = 'attachments:'
  if (!context.startsWith(prefix)) return []
  const raw = context.slice(prefix.length)
  if (!raw) return []
  return raw.split(',').map(s => s.trim()).filter(Boolean)
}

export type MessageCost = {
  promptCost: number | null
  completionCost: number | null
  requestCost: number | null
  totalCost: number
}

export type MessagePricing = {
  promptPrice: string | null
  completionPrice: string | null
  requestPrice: string | null
}

/**
 * Calculates the estimated cost for a message based on token usage and model pricing.
 * Pricing values are per-token strings from the OpenRouter API (e.g. "0.000005").
 * Returns null when no pricing or no usage data is available.
 */
function parsePrice(value: string | null | undefined): number {
  return value ? Number(value) : NaN
}

export function calculateMessageCost(
  usage: TokenUsage | null,
  model: { promptPrice?: string | null; completionPrice?: string | null; requestPrice?: string | null } | null | undefined
): MessageCost | null {
  if (!usage || !model) return null

  const promptRate = parsePrice(model.promptPrice)
  const completionRate = parsePrice(model.completionPrice)
  const requestRate = parsePrice(model.requestPrice)

  const hasPromptPricing = !Number.isNaN(promptRate) && usage.promptTokens != null
  const hasCompletionPricing = !Number.isNaN(completionRate) && usage.generationTokens != null
  const hasRequestPricing = !Number.isNaN(requestRate) && requestRate > 0
  const hasAnyPricing = hasPromptPricing || hasCompletionPricing || hasRequestPricing

  if (!hasAnyPricing) return null

  const promptCost = hasPromptPricing ? usage.promptTokens! * promptRate : null
  const completionCost = hasCompletionPricing ? usage.generationTokens! * completionRate : null
  const requestCost = hasRequestPricing ? requestRate : null
  const totalCost = (promptCost ?? 0) + (completionCost ?? 0) + (requestCost ?? 0)

  return { promptCost, completionCost, requestCost, totalCost }
}

/**
 * Formats a dollar cost into a human-readable string.
 * Uses adaptive precision: ≥ $0.01 → 2 decimals, $0.0001–$0.01 → 4 decimals,
 * < $0.0001 → scientific notation (e.g. "$5.0e-5").
 * Returns '$0.00' for zero and '—' for non-finite inputs (NaN, Infinity).
 */
export function formatCost(cost: number): string {
  if (!Number.isFinite(cost)) return '—'
  if (cost === 0) return '$0.00'
  if (cost < 0.0001) return `$${cost.toExponential(1)}`
  if (cost < 0.01) return `$${cost.toFixed(4)}`
  return `$${cost.toFixed(2)}`
}

/**
 * Formats a per-token price string (from OpenRouter API) into a human-readable
 * rate per 1M tokens. Returns null when the input is missing or not a number.
 */
export function formatRatePerMillion(priceStr: string | null | undefined): string | null {
  if (!priceStr) return null
  const rate = Number(priceStr)
  if (Number.isNaN(rate)) return null
  if (rate === 0) return '$0'
  const perMillion = rate * 1_000_000
  return perMillion >= 1 ? `$${perMillion.toFixed(2)}` : `$${perMillion.toFixed(4)}`
}

export function extractTokenUsage(response: SendMessageResponse | null): TokenUsage | null {
  if (!response) return null
  const { promptTokens, generationTokens, totalTokens } = response
  if (promptTokens == null && generationTokens == null && totalTokens == null) return null
  return {
    promptTokens: promptTokens ?? null,
    generationTokens: generationTokens ?? null,
    totalTokens: totalTokens ?? null
  }
}

export function appendTextToMessage(message: UIMessage, chunk: string): UIMessage {
  let updated = false
  const parts = message.parts.map(part => {
    if (part.type !== 'text') return part
    updated = true
    return {
      ...part,
      text: `${part.text || ''}${chunk}`
    }
  })

  return updated
    ? { ...message, parts }
    : {
        ...message,
        parts: [...message.parts, { type: 'text', text: chunk }]
      }
}

export function getFirstTextContent(message: UIMessage): string {
  return message.parts.find(
    (part): part is { type: 'text'; text: string } => part.type === 'text'
  )?.text || ''
}

export function resolveStreamOutcome(
  status: {
    receivedDone: boolean
    receivedError: boolean
    receivedContent: boolean
  },
  currentError: string | null
): {
  status: ChatStatus
  error: string | null
} {
  if (status.receivedError) {
    return {
      status: 'error',
      error: currentError || 'Streaming error'
    }
  }

  if (!status.receivedDone) {
    return {
      status: 'error',
      error: status.receivedContent
        ? 'The response stream was interrupted before completion. Partial response shown.'
        : 'The response stream was interrupted before completion.'
    }
  }

  return {
    status: 'ready',
    error: currentError
  }
}

export function useChatStore() {
  const {
    createChat,
    fetchChats,
    fetchChatMessages,
    sendMessage,
    streamMessage,
    editAndStreamMessage,
    fetchModels,
    updateChat,
    deleteChat
  } = useChatApi()
  const { settings } = useUserSettings()

  const conversations = useState<Conversation[]>('chat-conversations', () => [])
  const activeChatId = useState<string | null>('chat-active-id', () => null)
  const models = useState<ModelOption[]>('chat-models', () => [])
  const modelsLoading = useState<boolean>('chat-models-loading', () => false)
  const modelsError = useState<string | null>('chat-models-error', () => null)
  const tokenUsageMap = useState<Record<string, TokenUsage>>('chat-token-usage', () => ({}))
  const messagePricingMap = useState<Record<string, MessagePricing>>('chat-message-pricing', () => ({}))
  const reasoningMap = useState<Record<string, string>>('chat-reasoning', () => ({}))
  const webSearchEnabled = useState<boolean>('chat-web-search-enabled', () => false)
  const activeConversation = computed(() =>
    conversations.value.find(c => c.session.chatId === activeChatId.value) || null
  )

  const defaultModel = computed<ModelOption | null>(() => {
    const saved = settings.value.lastUsedModel
    if (saved) {
      const match = models.value.find(m => m.value === saved.value && m.provider === saved.provider)
      if (match) return match
    }
    return models.value[0] || null
  })

  const isFavorite = (model: ModelOption): boolean =>
    settings.value.favoriteModels.includes(favoriteModelKey(model))

  const toggleFavorite = (model: ModelOption) => {
    const key = favoriteModelKey(model)
    const favorites = settings.value.favoriteModels
    if (favorites.includes(key)) {
      settings.value.favoriteModels = favorites.filter(k => k !== key)
    } else {
      settings.value.favoriteModels = [...favorites, key]
    }
  }

  const sortedModels = computed<ModelOption[]>(() =>
    [...models.value].sort((a, b) => {
      const aFav = isFavorite(a) ? 0 : 1
      const bFav = isFavorite(b) ? 0 : 1
      if (aFav !== bFav) return aFav - bFav
      if (a.provider !== b.provider) {
        return PROVIDERS.indexOf(a.provider) - PROVIDERS.indexOf(b.provider)
      }
      return a.label.localeCompare(b.label)
    })
  )

  const toUiMessage = (message: BackendMessage): UIMessage => {
    const role =
      message.role === 'ASSISTANT'
        ? 'assistant'
        : message.role === 'SYSTEM'
          ? 'system'
          : 'user'

    const parts: UIMessage['parts'] = [{ type: 'text', text: message.message || '' }]

    if (message.context?.startsWith('attachments:')) {
      const mimeTypes = parseAttachmentContext(message.context)
      for (const mediaType of mimeTypes) {
        parts.push({ type: 'file', mediaType, url: '' })
      }
    }

    const id = message.id ? `msg-${message.id}` : messageId()

    if (role === 'assistant' && (message.promptTokens != null || message.generationTokens != null || message.totalTokens != null)) {
      tokenUsageMap.value[id] = {
        promptTokens: message.promptTokens ?? null,
        generationTokens: message.generationTokens ?? null,
        totalTokens: message.totalTokens ?? null
      }
    }

    const createdAt = message.createdAt ? new Date(message.createdAt) : undefined

    return { id, role, parts, createdAt }
  }

  watch(
    () => settings.value.backendBaseUrl,
    () => {
      // Reset cached data when the backend changes
      models.value = []
      conversations.value = []
      activeChatId.value = null
      tokenUsageMap.value = {}
      messagePricingMap.value = {}
      reasoningMap.value = {}
    }
  )

  const ensureModels = async () => {
    modelsLoading.value = true
    try {
      const results = await Promise.allSettled([
        ...PROVIDERS.map(provider => fetchModels(provider))
      ])

      const loaded: ModelOption[] = []
      const errors: string[] = []

      for (const result of results) {
        if (result.status === 'fulfilled') {
          loaded.push(...result.value)
        } else {
          const msg = result.reason instanceof Error ? result.reason.message : String(result.reason)
          errors.push(msg)
          console.error('Failed to load models for a provider:', msg)
        }
      }

      models.value = loaded
      modelsError.value = loaded.length === 0 && errors.length > 0
        ? errors[0]
        : null
    } catch (err) {
      console.error(err)
      modelsError.value =
        err instanceof Error ? err.message : 'Unable to load models from backend.'
    } finally {
      modelsLoading.value = false
    }

    return models.value
  }

  const loadMessagesForChat = async (chatId: string) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation || conversation.messages.length > 0) return

    const messages = await fetchChatMessages(chatId)
    const uiMessages = messages.map(toUiMessage)
    const lastUserMessage =
      [...messages].reverse().find(message => message.role === 'USER')?.message || null

    conversation.messages = uiMessages
    conversation.lastPrompt = lastUserMessage
  }

  const loadChatsFromBackend = async () => {
    const chats = await fetchChats()
    const existingConversations = new Map(
      conversations.value.map(conversation => [conversation.session.chatId, conversation])
    )

    const previousActiveId = activeChatId.value
    const existingSelectedId =
      previousActiveId && chats.some(chat => chat.chatId === previousActiveId)
        ? previousActiveId
        : null

    const loadedConversations: Conversation[] = chats.map(session => {
      const existingConversation = existingConversations.get(session.chatId)

      return {
        session: {
          ...existingConversation?.session,
          ...session
        },
        messages: existingConversation?.messages || [],
        status: existingConversation?.status || 'ready',
        error: existingConversation?.error || null,
        model: existingConversation?.model || defaultModel.value,
        activeRequest: existingConversation?.activeRequest,
        lastPrompt: existingConversation?.lastPrompt || null
      } satisfies Conversation
    })

    conversations.value = loadedConversations
    activeChatId.value = existingSelectedId || loadedConversations[0]?.session.chatId || null

    // Load messages only for the active chat
    if (activeChatId.value) {
      await loadMessagesForChat(activeChatId.value)
    }

    return loadedConversations
  }

  const startChat = async (title?: string) => {
    const session = await createChat(title ? { title } : {})
    const conversation: Conversation = {
      session,
      messages: [],
      status: 'ready',
      error: null,
      model: defaultModel.value,
      lastPrompt: null
    }

    conversations.value = [
      conversation,
      ...conversations.value.filter(c => c.session.chatId !== session.chatId)
    ]
    activeChatId.value = session.chatId
    return session
  }

  const selectChat = async (chatId: string) => {
    activeChatId.value = chatId
    await loadMessagesForChat(chatId)
  }

  const setModelForChat = (chatId: string, model: ModelOption) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation) {
      conversation.model = model
    }
  }

  const renameChat = async (chatId: string, title: string) => {
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      throw new Error('Chat title must not be empty.')
    }

    const updatedChat = await updateChat(chatId, { title: trimmedTitle })
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation) {
      conversation.session.title = updatedChat.title || trimmedTitle
    }
    return updatedChat
  }

  const removeChat = async (chatId: string) => {
    const wasActive = activeChatId.value === chatId
    stop(chatId)

    // Clean up token usage and reasoning entries for this chat's messages
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation) {
      for (const msg of conversation.messages) {
        delete tokenUsageMap.value[msg.id]
        delete messagePricingMap.value[msg.id]
        delete reasoningMap.value[msg.id]
      }
    }

    await deleteChat(chatId)
    conversations.value = conversations.value.filter(c => c.session.chatId !== chatId)

    if (wasActive) {
      activeChatId.value = conversations.value[0]?.session.chatId || null
    }
  }

  const stop = (chatId?: string) => {
    const id = chatId || activeChatId.value
    if (!id) return
    const conversation = conversations.value.find(c => c.session.chatId === id)
    if (conversation?.activeRequest) {
      conversation.activeRequest.abort()
      conversation.activeRequest = undefined
      conversation.status = 'ready'
    }
  }

  const pushMessage = (chatId: string, message: UIMessage) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) return
    conversation.messages.push(message)
  }

  /**
   * Replaces a streamed message using the slot captured when the placeholder was appended.
   * The hint index is the fast path for chunk updates; the message id remains authoritative
   * if the list has shifted before a replacement happens.
   */
  const replaceMessageAtIndex = (
    conversation: Conversation,
    hintIndex: number,
    messageId: string,
    message: UIMessage
  ) => {
    const targetIndex =
      hintIndex >= 0 &&
      hintIndex < conversation.messages.length &&
      conversation.messages[hintIndex]?.id === messageId
        ? hintIndex
        : conversation.messages.findIndex(existing => existing.id === messageId)

    if (targetIndex === -1) {
      return
    }

    conversation.messages.splice(targetIndex, 1, message)
  }

  const removeMessageIfEmpty = (conversation: Conversation, message: UIMessage) => {
    if (getFirstTextContent(message)) {
      return
    }

    conversation.messages = conversation.messages.filter(existing => existing.id !== message.id)
  }

  const sendPrompt = async (
    chatId: string,
    text: string,
    model: ModelOption,
    options?: {
      appendUserMessage?: boolean
      media?: MediaAttachment[]
    }
  ): Promise<UIMessage | null> => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) {
      throw new Error('No chat selected')
    }

    const appendUserMessage = options?.appendUserMessage ?? true

    stop(chatId) // cancel any ongoing request

    if (appendUserMessage) {
      const parts: UIMessage['parts'] = [{ type: 'text', text }]
      if (options?.media?.length) {
        for (const att of options.media) {
          parts.push({ type: 'file', mediaType: att.mimeType, filename: att.name, url: '' })
        }
      }
      const userMessage: UIMessage = {
        id: messageId(),
        role: 'user',
        parts,
        createdAt: new Date()
      }
      conversation.messages.push(userMessage)
    }

    conversation.status = 'submitted'
    conversation.error = null
    conversation.model = model
    conversation.lastPrompt = text

    const controller = new AbortController()
    conversation.activeRequest = controller

    // Create assistant message placeholder for streaming
    const assistantMessage: UIMessage = {
      id: messageId(),
      role: 'assistant',
      parts: [{ type: 'text', text: '' }],
      createdAt: new Date()
    }
    conversation.messages.push(assistantMessage)
    const assistantMessageIndex = conversation.messages.length - 1
    let currentAssistantMessage = assistantMessage
    const streamStatus = {
      receivedDone: false,
      receivedError: false,
      receivedContent: false
    }

    try {
      const input = {
        content: text,
        provider: model.provider,
        modelId: model.value,
        media: options?.media?.map(item => ({ mimeType: item.mimeType, data: item.data })),
        webSearchEnabled: webSearchEnabled.value || undefined
      }

      await streamMessage(
        chatId,
        input,
        (event: StreamEvent) => {
          switch (event.type) {
            case 'text':
              if (event.content) {
                streamStatus.receivedContent = true
                conversation.status = 'streaming'
                currentAssistantMessage = appendTextToMessage(currentAssistantMessage, event.content)
                replaceMessageAtIndex(
                  conversation,
                  assistantMessageIndex,
                  assistantMessage.id,
                  currentAssistantMessage
                )
              }
              break

            case 'reasoning':
              if (event.content) {
                streamStatus.receivedContent = true
                conversation.status = 'streaming'
                reasoningMap.value[assistantMessage.id] =
                  (reasoningMap.value[assistantMessage.id] || '') + event.content
              }
              break

            case 'done':
              streamStatus.receivedDone = true
              if (event.chatTitle) {
                conversation.session.title = event.chatTitle
              }
              if (
                event.promptTokens != null ||
                event.generationTokens != null ||
                event.totalTokens != null
              ) {
                tokenUsageMap.value[assistantMessage.id] = {
                  promptTokens: event.promptTokens ?? null,
                  generationTokens: event.generationTokens ?? null,
                  totalTokens: event.totalTokens ?? null
                }
                messagePricingMap.value[assistantMessage.id] = {
                  promptPrice: model.promptPrice ?? null,
                  completionPrice: model.completionPrice ?? null,
                  requestPrice: model.requestPrice ?? null
                }
              }
              break
          }
        },
        controller.signal
      )

      conversation.activeRequest = undefined
      const outcome = resolveStreamOutcome(streamStatus, conversation.error)
      conversation.status = outcome.status
      conversation.error = outcome.error

      if (outcome.status === 'error') {
        if (!getFirstTextContent(currentAssistantMessage)) {
          removeMessageIfEmpty(conversation, currentAssistantMessage)
          return null
        }
      }

      return currentAssistantMessage
    } catch (err) {
      // Remove empty/partial assistant message on error
      removeMessageIfEmpty(conversation, currentAssistantMessage)

      if (controller.signal.aborted) {
        conversation.error = 'Request cancelled.'
      } else {
        conversation.error =
          err instanceof Error ? err.message : 'Something went wrong while chatting.'
      }
      conversation.status = 'error'
      conversation.activeRequest = undefined
      return null
    }
  }

  const retryLastPrompt = async (chatId: string, model: ModelOption): Promise<UIMessage | null> => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) {
      throw new Error('No chat selected')
    }

    const lastPrompt = conversation.lastPrompt?.trim()
    if (!lastPrompt) {
      throw new Error('No previous prompt available for retry.')
    }

    return sendPrompt(chatId, lastPrompt, model, { appendUserMessage: false })
  }

  const saveLastUsedModel = (model: ModelOption) => {
    settings.value.lastUsedModel = model
  }

  /**
   * Edits an existing user message (identified by its backend numeric id),
   * truncates all subsequent messages in the UI, and streams a new AI response.
   */
  const editMessage = async (
    chatId: string,
    messageUiId: string,
    newText: string,
    model: ModelOption
  ): Promise<UIMessage | null> => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) {
      throw new Error('No chat selected')
    }

    // Only persisted messages have the "msg-{digits}" format
    if (!/^msg-\d+$/.test(messageUiId)) {
      throw new Error('Cannot edit a message that has not been persisted yet.')
    }
    const backendId = parseInt(messageUiId.slice(4), 10)

    stop(chatId)

    // Find the message index in the UI list
    const msgIndex = conversation.messages.findIndex(m => m.id === messageUiId)
    if (msgIndex === -1) {
      throw new Error('Message not found in conversation.')
    }

    // Snapshot current state so we can restore on pre-request failure
    const originalMessages = conversation.messages.slice()
    const originalLastPrompt = conversation.lastPrompt

    // Update the message text in the UI and remove all subsequent messages
    const messagesToRemove = conversation.messages.slice(msgIndex + 1)
    for (const msg of messagesToRemove) {
      delete tokenUsageMap.value[msg.id]
      delete messagePricingMap.value[msg.id]
      delete reasoningMap.value[msg.id]
    }
    conversation.messages.splice(msgIndex + 1)

    const textPart = conversation.messages[msgIndex].parts.find(
      (p): p is { type: 'text'; text: string } => p.type === 'text'
    )
    const originalText = textPart?.text ?? newText
    if (textPart) {
      textPart.text = newText
    }

    conversation.status = 'submitted'
    conversation.error = null
    conversation.model = model
    conversation.lastPrompt = newText

    const controller = new AbortController()
    conversation.activeRequest = controller

    // Create assistant message placeholder for streaming
    const assistantMessage: UIMessage = {
      id: messageId(),
      role: 'assistant',
      parts: [{ type: 'text', text: '' }],
      createdAt: new Date()
    }
    conversation.messages.push(assistantMessage)
    const assistantMessageIndex = conversation.messages.length - 1
    let currentAssistantMessage = assistantMessage
    const streamStatus = {
      receivedDone: false,
      receivedError: false,
      receivedContent: false
    }

    try {
      const input = {
        content: newText,
        provider: model.provider,
        modelId: model.value,
        webSearchEnabled: webSearchEnabled.value || undefined
      }

      await editAndStreamMessage(
        chatId,
        backendId,
        input,
        (event: StreamEvent) => {
          switch (event.type) {
            case 'text':
              if (event.content) {
                streamStatus.receivedContent = true
                conversation.status = 'streaming'
                currentAssistantMessage = appendTextToMessage(currentAssistantMessage, event.content)
                replaceMessageAtIndex(
                  conversation,
                  assistantMessageIndex,
                  assistantMessage.id,
                  currentAssistantMessage
                )
              }
              break

            case 'reasoning':
              if (event.content) {
                streamStatus.receivedContent = true
                conversation.status = 'streaming'
                reasoningMap.value[assistantMessage.id] =
                  (reasoningMap.value[assistantMessage.id] || '') + event.content
              }
              break

            case 'done':
              streamStatus.receivedDone = true
              if (
                event.promptTokens != null ||
                event.generationTokens != null ||
                event.totalTokens != null
              ) {
                tokenUsageMap.value[assistantMessage.id] = {
                  promptTokens: event.promptTokens ?? null,
                  generationTokens: event.generationTokens ?? null,
                  totalTokens: event.totalTokens ?? null
                }
                messagePricingMap.value[assistantMessage.id] = {
                  promptPrice: model.promptPrice ?? null,
                  completionPrice: model.completionPrice ?? null,
                  requestPrice: model.requestPrice ?? null
                }
              }
              break

            case 'error':
              streamStatus.receivedError = true
              conversation.error = event.content || 'Streaming error'
              break
          }
        },
        controller.signal
      )

      conversation.activeRequest = undefined
      const outcome = resolveStreamOutcome(streamStatus, conversation.error)
      conversation.status = outcome.status
      conversation.error = outcome.error

      if (outcome.status === 'error') {
        if (!getFirstTextContent(currentAssistantMessage)) {
          removeMessageIfEmpty(conversation, currentAssistantMessage)
          return null
        }
      }

      return currentAssistantMessage
    } catch (err) {
      // If the request was aborted, or streaming had already started (assistant has content),
      // keep the partial state. Otherwise restore the original messages.
      const streamedText = getFirstTextContent(currentAssistantMessage)
      if (!streamedText && !controller.signal.aborted) {
        // No content was streamed yet — restore the original message list
        conversation.messages = originalMessages
        if (textPart) {
          textPart.text = originalText
        }
        conversation.lastPrompt = originalLastPrompt
        // Clean up any entries for removed messages we can no longer see
        for (const msg of messagesToRemove) {
          delete tokenUsageMap.value[msg.id]
          delete reasoningMap.value[msg.id]
        }
      } else if (!streamedText) {
        // Aborted with no content — remove the empty assistant placeholder
        removeMessageIfEmpty(conversation, currentAssistantMessage)
      }

      if (controller.signal.aborted) {
        conversation.error = 'Request cancelled.'
      } else {
        conversation.error =
          err instanceof Error ? err.message : 'Something went wrong while chatting.'
      }
      conversation.status = 'error'
      conversation.activeRequest = undefined
      return null
    }
  }


  return {
    conversations,
    activeChatId,
    activeConversation,
    models,
    sortedModels,
    modelsLoading,
    modelsError,
    defaultModel,
    tokenUsageMap,
    messagePricingMap,
    reasoningMap,
    webSearchEnabled,
    isFavorite,
    toggleFavorite,
    ensureModels,
    loadChatsFromBackend,
    loadMessagesForChat,
    startChat,
    selectChat,
    setModelForChat,
    saveLastUsedModel,
    renameChat,
    removeChat,
    sendPrompt,
    retryLastPrompt,
    editMessage,
    stop
  }
}
