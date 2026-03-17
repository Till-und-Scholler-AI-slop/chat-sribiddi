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
  const reasoningMap = useState<Record<string, string>>('chat-reasoning', () => ({}))

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

    return { id, role, parts }
  }

  watch(
    () => settings.value.backendBaseUrl,
    () => {
      // Reset cached data when the backend changes
      models.value = []
      conversations.value = []
      activeChatId.value = null
      tokenUsageMap.value = {}
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
        parts
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
      parts: [{ type: 'text', text: '' }]
    }
    conversation.messages.push(assistantMessage)

    try {
      const input = {
        content: text,
        provider: model.provider,
        modelId: model.value,
        media: options?.media?.map(item => ({ mimeType: item.mimeType, data: item.data }))
      }

      await streamMessage(
        chatId,
        input,
        (event: StreamEvent) => {
          const textPart = assistantMessage.parts.find(
            (p): p is { type: 'text'; text: string } => p.type === 'text'
          )

          switch (event.type) {
            case 'text':
              if (textPart && event.content) {
                textPart.text += event.content
              }
              break

            case 'reasoning':
              if (event.content) {
                reasoningMap.value[assistantMessage.id] =
                  (reasoningMap.value[assistantMessage.id] || '') + event.content
              }
              break

            case 'done':
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
              }
              break

            case 'error':
              conversation.error = event.content || 'Streaming error'
              break
          }
        },
        controller.signal
      )

      conversation.status = 'ready'
      conversation.activeRequest = undefined

      return assistantMessage
    } catch (err) {
      // Remove empty/partial assistant message on error
      const textPart = assistantMessage.parts.find(
        (p): p is { type: 'text'; text: string } => p.type === 'text'
      )
      if (!textPart?.text) {
        const idx = conversation.messages.indexOf(assistantMessage)
        if (idx !== -1) conversation.messages.splice(idx, 1)
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

    // Resolve the numeric backend id from the UI message id (format "msg-{id}")
    const backendId = messageUiId.startsWith('msg-')
      ? parseInt(messageUiId.slice(4), 10)
      : NaN
    if (isNaN(backendId)) {
      throw new Error('Cannot edit a message that has not been persisted yet.')
    }

    stop(chatId)

    // Find the message index in the UI list
    const msgIndex = conversation.messages.findIndex(m => m.id === messageUiId)
    if (msgIndex === -1) {
      throw new Error('Message not found in conversation.')
    }

    // Update the message text in the UI and remove all subsequent messages
    const messagesToRemove = conversation.messages.slice(msgIndex + 1)
    for (const msg of messagesToRemove) {
      delete tokenUsageMap.value[msg.id]
      delete reasoningMap.value[msg.id]
    }
    conversation.messages.splice(msgIndex + 1)

    const textPart = conversation.messages[msgIndex].parts.find(
      (p): p is { type: 'text'; text: string } => p.type === 'text'
    )
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
      parts: [{ type: 'text', text: '' }]
    }
    conversation.messages.push(assistantMessage)

    try {
      const input = {
        content: newText,
        provider: model.provider,
        modelId: model.value
      }

      await editAndStreamMessage(
        chatId,
        backendId,
        input,
        (event: StreamEvent) => {
          const textPart = assistantMessage.parts.find(
            (p): p is { type: 'text'; text: string } => p.type === 'text'
          )

          switch (event.type) {
            case 'text':
              if (textPart && event.content) {
                textPart.text += event.content
              }
              break

            case 'reasoning':
              if (event.content) {
                reasoningMap.value[assistantMessage.id] =
                  (reasoningMap.value[assistantMessage.id] || '') + event.content
              }
              break

            case 'done':
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
              }
              break

            case 'error':
              conversation.error = event.content || 'Streaming error'
              break
          }
        },
        controller.signal
      )

      conversation.status = 'ready'
      conversation.activeRequest = undefined

      return assistantMessage
    } catch (err) {
      const textPart = assistantMessage.parts.find(
        (p): p is { type: 'text'; text: string } => p.type === 'text'
      )
      if (!textPart?.text) {
        const idx = conversation.messages.indexOf(assistantMessage)
        if (idx !== -1) conversation.messages.splice(idx, 1)
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
    reasoningMap,
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
