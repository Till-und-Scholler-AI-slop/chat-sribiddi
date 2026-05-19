import type { ChatStatus, UIMessage } from 'ai'
import type { StreamEvent } from '~/composables/chat-stream'
import type { BackendMessage, ChatSession, ImportChat, MediaAttachment, ModelOption, TokenUsage } from '~/composables/useChatApi'
import type { MessagePricing } from '~/composables/chat-helpers'
import { useChatApi } from '~/composables/useChatApi'
import { useUserSettings } from '~/composables/useUserSettings'
import {
  appendTextToMessage,
  getFirstTextContent,
  messageId,
  parseAttachmentContext,
  resolveStreamOutcome
} from '~/composables/chat-helpers'

type Conversation = {
  session: ChatSession
  messages: UIMessage[]
  status: ChatStatus
  error: string | null
  activeRequest?: AbortController
  model?: ModelOption | null
  lastPrompt: string | null
  local?: boolean
}

type StoredGuestMessage = Omit<UIMessage, 'createdAt'> & {
  createdAt?: string | null
}

type StoredGuestChat = {
  session: ChatSession
  messages: StoredGuestMessage[]
  model?: ModelOption | null
  lastPrompt: string | null
}

const GUEST_CHAT_STORAGE_KEY = 'chat-sribiddi:guest-chats:v1'

export function useChatStore() {
  const {
    createChat,
    fetchChats,
    fetchChatMessages,
    streamMessage,
    streamAnonymousMessage,
    editAndStreamMessage,
    updateChat,
    deleteChat,
    importChats
  } = useChatApi()
  const { status: authStatus } = useAuth()
  const { settings } = useUserSettings()
  const {
    models,
    sortedModels,
    modelsLoading,
    modelsError,
    defaultModel,
    isFavorite,
    toggleFavorite,
    ensureModels,
    saveLastUsedModel,
    resolveModel,
    resetModels
  } = useModelStore()
  const {
    clearToolCallMetadata,
    resetToolCallState,
    rekeyToolCallMetadata,
    setToolCallAvailability,
    hasToolCalls,
    getToolCalls,
    getToolCallError,
    isToolCallLoading,
    ensureToolCalls
  } = useToolCallStore()

  const conversations = useState<Conversation[]>('chat-conversations', () => [])
  const activeChatId = useState<string | null>('chat-active-id', () => null)
  const tokenUsageMap = useState<Record<string, TokenUsage>>('chat-token-usage', () => ({}))
  const messagePricingMap = useState<Record<string, MessagePricing>>('chat-message-pricing', () => ({}))
  const reasoningMap = useState<Record<string, string>>('chat-reasoning', () => ({}))
  const webSearchEnabled = useState<boolean>('chat-web-search-enabled', () => false)
  const activeConversation = computed(() =>
    conversations.value.find(c => c.session.chatId === activeChatId.value) || null
  )
  const isAuthenticated = computed(() => authStatus.value === 'authenticated')
  const isGuestMode = computed(() => !isAuthenticated.value)

  const createLocalId = (prefix: string) => {
    const uuid = globalThis.crypto?.randomUUID?.()
    if (uuid) return `${prefix}-${uuid}`
    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }

  const readGuestChats = (): StoredGuestChat[] => {
    if (!import.meta.client) return []
    try {
      const raw = localStorage.getItem(GUEST_CHAT_STORAGE_KEY)
      if (!raw) return []
      const parsed = JSON.parse(raw) as { chats?: StoredGuestChat[] }
      return Array.isArray(parsed.chats) ? parsed.chats : []
    } catch {
      return []
    }
  }

  const writeGuestChats = (items: StoredGuestChat[]) => {
    if (!import.meta.client) return
    localStorage.setItem(GUEST_CHAT_STORAGE_KEY, JSON.stringify({ chats: items }))
  }

  const clearGuestChats = () => {
    if (!import.meta.client) return
    localStorage.removeItem(GUEST_CHAT_STORAGE_KEY)
  }

  const persistGuestConversations = () => {
    const localChats: StoredGuestChat[] = conversations.value
      .filter(conversation => conversation.local)
      .map(conversation => ({
        session: conversation.session,
        messages: conversation.messages.map(message => ({
          ...message,
          createdAt: message.createdAt instanceof Date
            ? message.createdAt.toISOString()
            : message.createdAt
        })),
        model: conversation.model ?? null,
        lastPrompt: conversation.lastPrompt
      }))
    writeGuestChats(localChats)
  }

  const guestChatsForImport = (): ImportChat[] =>
    readGuestChats()
      .slice(0, 30)
      .map(item => ({
        title: item.session.title || null,
        createdAt: item.session.createdAt || null,
        messages: item.messages
          .slice(0, 100)
          .map(message => {
            const text = getFirstTextContent(message as UIMessage)
            if (!text.trim()) return null
            const role =
              message.role === 'assistant'
                ? 'ASSISTANT'
                : message.role === 'system'
                  ? 'SYSTEM'
                  : 'USER'
            return {
              role,
              message: text,
              createdAt: message.createdAt || null
            } satisfies ImportChat['messages'][number]
          })
          .filter((message): message is ImportChat['messages'][number] => message !== null)
      }))
      .filter(chat => chat.messages.length > 0)

  const hasGuestChats = () => guestChatsForImport().length > 0

  const guestChatCount = () => guestChatsForImport().length

  const importGuestChats = async () => {
    if (!isAuthenticated.value) {
      throw new Error('401 Unauthorized')
    }

    const payload = guestChatsForImport()
    if (payload.length === 0) {
      clearGuestChats()
      conversations.value = conversations.value.filter(conversation => !conversation.local)
      return []
    }

    const imported = await importChats(payload)
    clearGuestChats()
    settings.value.guestImportDismissed = false
    await loadChatsFromBackend()
    return imported
  }

  const clearMessageMetadata = (messageId: string) => {
    Reflect.deleteProperty(tokenUsageMap.value, messageId)
    Reflect.deleteProperty(messagePricingMap.value, messageId)
    Reflect.deleteProperty(reasoningMap.value, messageId)
    clearToolCallMetadata(messageId)
  }

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

    if (role === 'assistant') {
      setToolCallAvailability(id, message.hasToolCalls === true)
    }

    const createdAt = message.createdAt ? new Date(message.createdAt) : undefined

    return { id, role, parts, createdAt }
  }

  watch(
    () => settings.value.backendBaseUrl,
    () => {
      // Reset cached server-backed data when the backend changes.
      resetModels()
      conversations.value = []
      activeChatId.value = null
      tokenUsageMap.value = {}
      messagePricingMap.value = {}
      reasoningMap.value = {}
      resetToolCallState()
    }
  )

  watch(
    () => settings.value.expertMode,
    () => {
      resetModels()
    }
  )

  const loadMessagesForChat = async (chatId: string) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation || conversation.local || conversation.messages.length > 0) return

    const messages = await fetchChatMessages(chatId)
    const uiMessages = messages.map(toUiMessage)
    const lastUserMessage =
      [...messages].reverse().find(message => message.role === 'USER')?.message || null

    conversation.messages = uiMessages
    conversation.lastPrompt = lastUserMessage
  }

  const loadChatsFromBackend = async () => {
    if (!isAuthenticated.value) {
      return loadGuestChats()
    }

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
        model: resolveModel(existingConversation?.model) || defaultModel.value,
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

  const loadGuestChats = async () => {
    const stored = readGuestChats()
    const loadedConversations: Conversation[] = stored.map(item => ({
      session: item.session,
      messages: item.messages.map(message => ({
        ...message,
        createdAt: message.createdAt ? new Date(message.createdAt) : undefined
      })),
      status: 'ready',
      error: null,
      model: resolveModel(item.model) || defaultModel.value,
      lastPrompt: item.lastPrompt,
      local: true
    }))

    conversations.value = loadedConversations
    activeChatId.value = loadedConversations[0]?.session.chatId || null
    return loadedConversations
  }

  const startChat = async (title?: string) => {
    if (!isAuthenticated.value) {
      const now = new Date().toISOString()
      const session: ChatSession = {
        chatId: createLocalId('guest-chat'),
        title: title?.trim() || null,
        createdAt: now,
        userId: null
      }
      const conversation: Conversation = {
        session,
        messages: [],
        status: 'ready',
        error: null,
        model: defaultModel.value,
        lastPrompt: null,
        local: true
      }
      conversations.value = [
        conversation,
        ...conversations.value.filter(c => c.session.chatId !== session.chatId)
      ]
      activeChatId.value = session.chatId
      persistGuestConversations()
      return session
    }

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
    if (isAuthenticated.value) {
      await loadMessagesForChat(chatId)
    }
  }

  const setModelForChat = (chatId: string, model: ModelOption) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation) {
      conversation.model = resolveModel(model) || defaultModel.value
      if (conversation.local) {
        persistGuestConversations()
      }
    }
  }

  const renameChat = async (chatId: string, title: string) => {
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      throw new Error('Chat title must not be empty.')
    }

    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation?.local) {
      conversation.session.title = trimmedTitle
      persistGuestConversations()
      return conversation.session
    }

    const updatedChat = await updateChat(chatId, { title: trimmedTitle })
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
        clearMessageMetadata(msg.id)
      }
    }

    if (!conversation?.local) {
      await deleteChat(chatId)
    }
    conversations.value = conversations.value.filter(c => c.session.chatId !== chatId)

    if (wasActive) {
      activeChatId.value = conversations.value[0]?.session.chatId || null
    }
    if (conversation?.local) {
      persistGuestConversations()
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
    clearMessageMetadata(message.id)
  }

  const rekeyStateEntry = <T>(map: Record<string, T>, oldId: string, newId: string) => {
    if (oldId === newId || !(oldId in map)) return
    map[newId] = map[oldId]
    Reflect.deleteProperty(map, oldId)
  }

  const rekeyMessageMetadata = (oldId: string, newId: string) => {
    rekeyStateEntry(tokenUsageMap.value, oldId, newId)
    rekeyStateEntry(messagePricingMap.value, oldId, newId)
    rekeyStateEntry(reasoningMap.value, oldId, newId)
    rekeyToolCallMetadata(oldId, newId)
  }

  const finalizeAssistantMessage = (
    conversation: Conversation,
    hintIndex: number,
    currentMessage: UIMessage,
    event: Pick<StreamEvent, 'messageId' | 'hasToolCalls'>
  ) => {
    const nextId = event.messageId != null ? `msg-${event.messageId}` : currentMessage.id
    const updatedMessage = nextId === currentMessage.id ? currentMessage : { ...currentMessage, id: nextId }
    if (nextId !== currentMessage.id) {
      replaceMessageAtIndex(conversation, hintIndex, currentMessage.id, updatedMessage)
      rekeyMessageMetadata(currentMessage.id, nextId)
    }
    if (event.hasToolCalls != null) {
      setToolCallAvailability(nextId, event.hasToolCalls)
    }
    return updatedMessage
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

    const effectiveModel = resolveModel(model) || defaultModel.value
    if (!effectiveModel) return null

    const appendUserMessage = options?.appendUserMessage ?? true
    const history = conversation.local
      ? conversation.messages
          .map(message => {
            const text = getFirstTextContent(message)
            if (!text.trim()) return null
            const role = message.role === 'assistant'
              ? 'ASSISTANT'
              : message.role === 'system'
                ? 'SYSTEM'
                : 'USER'
            return { role, message: text } as const
          })
          .filter((item): item is { role: 'USER' | 'ASSISTANT' | 'SYSTEM'; message: string } => item !== null)
      : []

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

    conversation.model = effectiveModel
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
        provider: effectiveModel.provider,
        modelId: effectiveModel.value,
        media: options?.media?.map(item => ({ mimeType: item.mimeType, data: item.data })),
        webSearchEnabled: webSearchEnabled.value || undefined
      }

      const stream = conversation.local
        ? streamAnonymousMessage(
            {
              content: input.content,
              provider: input.provider,
              modelId: input.modelId,
              media: input.media,
              history
            },
            handleStreamEvent,
            controller.signal
          )
        : streamMessage(
            chatId,
            input,
            handleStreamEvent,
            controller.signal
          )

      function handleStreamEvent(event: StreamEvent) {
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
              reasoningMap.value[currentAssistantMessage.id] =
                (reasoningMap.value[currentAssistantMessage.id] || '') + event.content
            }
            break

          case 'done':
            streamStatus.receivedDone = true
            currentAssistantMessage = finalizeAssistantMessage(
              conversation,
              assistantMessageIndex,
              currentAssistantMessage,
              event
            )
            if (event.chatTitle) {
              conversation.session.title = event.chatTitle
            } else if (conversation.local && !conversation.session.title) {
              conversation.session.title = text.length > 72 ? `${text.slice(0, 72).trim()}...` : text
            }
            if (
              event.promptTokens != null ||
              event.generationTokens != null ||
              event.totalTokens != null
            ) {
              tokenUsageMap.value[currentAssistantMessage.id] = {
                promptTokens: event.promptTokens ?? null,
                generationTokens: event.generationTokens ?? null,
                totalTokens: event.totalTokens ?? null
              }
              messagePricingMap.value[currentAssistantMessage.id] = {
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
      }

      await stream

      conversation.activeRequest = undefined
      const outcome = resolveStreamOutcome(streamStatus, conversation.error)
      conversation.status = outcome.status
      conversation.error = outcome.error
      if (conversation.local) {
        persistGuestConversations()
      }

      if (outcome.status === 'error') {
        if (!getFirstTextContent(currentAssistantMessage)) {
          removeMessageIfEmpty(conversation, currentAssistantMessage)
          if (conversation.local) {
            persistGuestConversations()
          }
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
      if (conversation.local) {
        persistGuestConversations()
      }
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
      clearMessageMetadata(msg.id)
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
                reasoningMap.value[currentAssistantMessage.id] =
                  (reasoningMap.value[currentAssistantMessage.id] || '') + event.content
              }
              break

            case 'done':
              streamStatus.receivedDone = true
              currentAssistantMessage = finalizeAssistantMessage(
                conversation,
                assistantMessageIndex,
                currentAssistantMessage,
                event
              )
              if (
                event.promptTokens != null ||
                event.generationTokens != null ||
                event.totalTokens != null
              ) {
                tokenUsageMap.value[currentAssistantMessage.id] = {
                  promptTokens: event.promptTokens ?? null,
                  generationTokens: event.generationTokens ?? null,
                  totalTokens: event.totalTokens ?? null
                }
                messagePricingMap.value[currentAssistantMessage.id] = {
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
          clearMessageMetadata(msg.id)
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
    hasToolCalls,
    getToolCalls,
    getToolCallError,
    isToolCallLoading,
    ensureToolCalls,
    webSearchEnabled,
    isGuestMode,
    isFavorite,
    toggleFavorite,
    ensureModels,
    loadChatsFromBackend,
    loadGuestChats,
    hasGuestChats,
    guestChatCount,
    guestChatsForImport,
    importGuestChats,
    clearGuestChats,
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
