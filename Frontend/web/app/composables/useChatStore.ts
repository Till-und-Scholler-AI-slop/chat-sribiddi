import type { ChatStatus, UIMessage } from 'ai'
import { isToolCallStreamEvent } from '~/composables/chat-stream'
import type { StreamEvent } from '~/composables/chat-stream'
import type { BackendMessage, ChatSession, ImportChat, MediaAttachment, ModelOption, TokenUsage } from '~/composables/useChatApi'
import type { MessagePricing } from '~/composables/chat-helpers'
import { useChatApi } from '~/composables/useChatApi'
import { useUserSettings } from '~/composables/useUserSettings'
import { chooseDefaultModel, isOpenRouterFreeModel } from '~/composables/useModelStore'
import {
  appendTextToMessage,
  getFirstTextContent,
  messageId,
  parseAttachmentContext,
  resolveStreamOutcome
} from '~/composables/chat-helpers'
import { isLegacyProvider, rekeyStateEntry } from '~/utils/model-utils'
import {
  clearGuestChatRecords,
  enqueueGuestChatStorageTask,
  loadGuestChatRecords,
  saveGuestChatRecords,
  stageGuestChatRecords
} from '~/utils/guest-chat-storage'

type Conversation = {
  session: ChatSession
  messages: UIMessage[]
  status: ChatStatus
  error: string | null
  activeRequest?: AbortController
  model?: ModelOption | null
  lastPrompt: string | null
  local?: boolean
  requestGeneration: number
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
const AUTHENTICATED_PARALLEL_STREAM_LIMIT = 1
const MAX_STORED_GUEST_CHATS = 30
const MAX_STORED_GUEST_MESSAGES = 100

type ChatCoordinator = {
  chatLoadGeneration: number
  chatLoadKey: string | null
  chatLoadedKey: string | null
  chatLoadPromise: Promise<Conversation[]> | null
  messageLoads: Map<string, Promise<void>>
  guestLoaded: boolean
  guestLoadPromise: Promise<StoredGuestChat[]> | null
  persistTimer: ReturnType<typeof setTimeout> | null
  persistChain: Promise<void>
}

const chatCoordinators = new WeakMap<object, ChatCoordinator>()

function coordinatorFor(key: object): ChatCoordinator {
  let coordinator = chatCoordinators.get(key)
  if (!coordinator) {
    coordinator = {
      chatLoadGeneration: 0,
      chatLoadKey: null,
      chatLoadedKey: null,
      chatLoadPromise: null,
      messageLoads: new Map(),
      guestLoaded: false,
      guestLoadPromise: null,
      persistTimer: null,
      persistChain: Promise.resolve()
    }
    chatCoordinators.set(key, coordinator)
  }
  return coordinator
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function isCurrentConversationRequest(
  conversation: Pick<Conversation, 'activeRequest' | 'requestGeneration'>,
  controller: AbortController,
  generation: number
) {
  return conversation.activeRequest === controller
    && conversation.requestGeneration === generation
}

function normalizeStoredGuestChat(value: unknown): StoredGuestChat | null {
  if (!isRecord(value) || !isRecord(value.session) || !Array.isArray(value.messages)) return null
  if (typeof value.session.chatId !== 'string' || !value.session.chatId.trim()) return null

  const messages = value.messages.flatMap(raw => {
    if (!isRecord(raw) || typeof raw.id !== 'string' || !Array.isArray(raw.parts)) return []
    if (raw.role !== 'user' && raw.role !== 'assistant' && raw.role !== 'system') return []
    const parts = raw.parts.filter(part => {
      if (!isRecord(part) || typeof part.type !== 'string') return false
      if (part.type === 'text') return typeof part.text === 'string'
      if (part.type === 'file') return typeof part.mediaType === 'string'
      return false
    }) as UIMessage['parts']
    if (parts.length === 0) return []
    return [{
      id: raw.id,
      role: raw.role,
      parts,
      createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : null
    } satisfies StoredGuestMessage]
  })

  const model = isRecord(value.model)
    && typeof value.model.label === 'string'
    && typeof value.model.value === 'string'
    && typeof value.model.provider === 'string'
    ? value.model as ModelOption
    : null

  return {
    session: {
      chatId: value.session.chatId,
      title: typeof value.session.title === 'string' ? value.session.title : null,
      createdAt: typeof value.session.createdAt === 'string' ? value.session.createdAt : null,
      userId: null
    },
    messages: messages.slice(-MAX_STORED_GUEST_MESSAGES),
    model,
    lastPrompt: typeof value.lastPrompt === 'string' ? value.lastPrompt : null
  }
}

export function useChatStore() {
  const coordinator = coordinatorFor(useNuxtApp())
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
    sortedModels: allSortedModels,
    modelsLoading,
    modelsError,
    defaultModel: userDefaultModel,
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
    ingestLiveToolCall,
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
  const guestChatCache = useState<StoredGuestChat[]>('chat-guest-cache-v2', () => [])
  const activeConversation = computed(() =>
    conversations.value.find(c => c.session.chatId === activeChatId.value) || null
  )
  const isAuthenticated = computed(() => authStatus.value === 'authenticated')
  const isGuestMode = computed(() => !isAuthenticated.value)
  const openRouterFreeModel = computed<ModelOption | null>(() =>
    chooseDefaultModel(allSortedModels.value, null, { forceOpenRouterFree: true })
  )
  const sortedModels = computed<ModelOption[]>(() =>
    isGuestMode.value || activeConversation.value?.local
      ? allSortedModels.value.filter(isOpenRouterFreeModel)
      : allSortedModels.value
  )
  const defaultModelFor = (conversation?: Pick<Conversation, 'local'> | null) =>
    isGuestMode.value || conversation?.local
      ? openRouterFreeModel.value
      : userDefaultModel.value

  const defaultModel = computed<ModelOption | null>(() => defaultModelFor(activeConversation.value))

  const resolveAllowedModel = (
    model: ModelOption | null | undefined,
    conversation?: Pick<Conversation, 'local'> | null
  ) => {
    if (isGuestMode.value || conversation?.local) {
      return openRouterFreeModel.value
    }
    return resolveModel(model) || defaultModelFor(conversation)
  }

  const createLocalId = (prefix: string) => {
    const uuid = globalThis.crypto?.randomUUID?.()
    if (uuid) return `${prefix}-${uuid}`
    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }

  const PERSIST_DEBOUNCE_MS = 250

  const hydrateGuestChatCache = async () => {
    if (coordinator.guestLoaded) return guestChatCache.value
    if (coordinator.guestLoadPromise) return coordinator.guestLoadPromise

    const promise = (async () => {
      const raw = await loadGuestChatRecords(GUEST_CHAT_STORAGE_KEY)
      const normalized = raw
        .map(normalizeStoredGuestChat)
        .filter((chat): chat is StoredGuestChat => chat !== null)
        .slice(0, MAX_STORED_GUEST_CHATS)
      guestChatCache.value = normalized
      coordinator.guestLoaded = true
      return normalized
    })()
    coordinator.guestLoadPromise = promise
    try {
      return await promise
    } finally {
      if (coordinator.guestLoadPromise === promise) coordinator.guestLoadPromise = null
    }
  }

  const cancelPendingGuestPersist = () => {
    if (!coordinator.persistTimer) return
    clearTimeout(coordinator.persistTimer)
    coordinator.persistTimer = null
  }

  const clearGuestChats = async () => {
    cancelPendingGuestPersist()
    guestChatCache.value = []
    coordinator.guestLoaded = true
    coordinator.persistChain = enqueueGuestChatStorageTask(
      coordinator.persistChain,
      () => clearGuestChatRecords(GUEST_CHAT_STORAGE_KEY)
    )
    await coordinator.persistChain
  }

  const currentGuestConversationSnapshot = (): StoredGuestChat[] =>
    conversations.value
      .filter(conversation => conversation.local)
      .slice(0, MAX_STORED_GUEST_CHATS)
      .map(conversation => ({
        session: conversation.session,
        messages: conversation.messages
          .slice(-MAX_STORED_GUEST_MESSAGES)
          .map(message => ({
            ...message,
            createdAt: message.createdAt instanceof Date
              ? message.createdAt.toISOString()
              : message.createdAt
          })),
        model: conversation.model ?? null,
        lastPrompt: conversation.lastPrompt
      }))

  const writeCurrentGuestConversations = () => {
    const snapshot = currentGuestConversationSnapshot()
    guestChatCache.value = snapshot
    // Stage synchronously so a reload cannot race the queued IndexedDB write.
    const stagedAt = stageGuestChatRecords(snapshot, GUEST_CHAT_STORAGE_KEY)
    coordinator.persistChain = enqueueGuestChatStorageTask(
      coordinator.persistChain,
      () => saveGuestChatRecords(snapshot, GUEST_CHAT_STORAGE_KEY, stagedAt)
    )
    return coordinator.persistChain
  }

  const persistGuestConversations = (options: { immediate?: boolean } = {}) => {
    if (options.immediate) {
      cancelPendingGuestPersist()
      void writeCurrentGuestConversations()
      return
    }

    cancelPendingGuestPersist()
    coordinator.persistTimer = setTimeout(() => {
      coordinator.persistTimer = null
      void writeCurrentGuestConversations()
    }, PERSIST_DEBOUNCE_MS)
  }

  const guestChatsForImport = (): ImportChat[] =>
    guestChatCache.value
      .slice(0, MAX_STORED_GUEST_CHATS)
      .map(item => ({
        title: item.session.title || null,
        createdAt: item.session.createdAt || null,
        messages: item.messages
          .slice(-MAX_STORED_GUEST_MESSAGES)
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

    await hydrateGuestChatCache()
    const payload = guestChatsForImport()
    if (payload.length === 0) {
      await clearGuestChats()
      conversations.value = conversations.value.filter(conversation => !conversation.local)
      return []
    }

    const imported = await importChats(payload)
    await clearGuestChats()
    settings.value.guestImportDismissed = false
    await loadChatsFromBackend(true)
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
      coordinator.chatLoadGeneration++
      coordinator.chatLoadKey = null
      coordinator.chatLoadedKey = null
      coordinator.chatLoadPromise = null
      coordinator.messageLoads.clear()
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
    const requestedChatId = chatId
    const conversation = conversations.value.find(c => c.session.chatId === requestedChatId)
    if (!conversation || conversation.local || conversation.messages.length > 0) return
    const generation = coordinator.chatLoadGeneration
    const loadKey = `${generation}:${requestedChatId}`
    const existing = coordinator.messageLoads.get(loadKey)
    if (existing) return existing

    const promise = (async () => {
      const messages = await fetchChatMessages(requestedChatId)
      if (generation !== coordinator.chatLoadGeneration) return
      if (activeChatId.value !== requestedChatId) return
      const current = conversations.value.find(c => c.session.chatId === requestedChatId)
      if (!current || current.local || current.messages.length > 0) return
      const uiMessages = messages.map(toUiMessage)
      const lastUserMessage =
        [...messages].reverse().find(message => message.role === 'USER')?.message || null

      current.messages = uiMessages
      current.lastPrompt = lastUserMessage
    })()
    coordinator.messageLoads.set(loadKey, promise)
    try {
      await promise
    } finally {
      if (coordinator.messageLoads.get(loadKey) === promise) {
        coordinator.messageLoads.delete(loadKey)
      }
    }
  }

  const loadChatsFromBackend = async (force = false) => {
    await hydrateGuestChatCache()
    if (!isAuthenticated.value) {
      return loadGuestChats()
    }
    const requestKey = settings.value.backendBaseUrl.trim()
    if (!force && coordinator.chatLoadedKey === requestKey) return conversations.value
    if (coordinator.chatLoadPromise && coordinator.chatLoadKey === requestKey) {
      return coordinator.chatLoadPromise
    }

    const generation = ++coordinator.chatLoadGeneration
    coordinator.chatLoadKey = requestKey
    const promise = (async () => {
      const chats = await fetchChats()
      if (
        generation !== coordinator.chatLoadGeneration
        || coordinator.chatLoadKey !== requestKey
        || !isAuthenticated.value
      ) return conversations.value

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
          session: { ...existingConversation?.session, ...session },
          messages: existingConversation?.messages || [],
          status: existingConversation?.status || 'ready',
          error: existingConversation?.error || null,
          model: resolveAllowedModel(existingConversation?.model, existingConversation),
          activeRequest: existingConversation?.activeRequest,
          lastPrompt: existingConversation?.lastPrompt || null,
          requestGeneration: existingConversation?.requestGeneration || 0
        } satisfies Conversation
      })

      conversations.value = loadedConversations
      coordinator.chatLoadedKey = requestKey
      activeChatId.value = existingSelectedId || loadedConversations[0]?.session.chatId || null
      if (activeChatId.value) await loadMessagesForChat(activeChatId.value)
      return loadedConversations
    })()
    coordinator.chatLoadPromise = promise
    try {
      return await promise
    } finally {
      if (coordinator.chatLoadPromise === promise) coordinator.chatLoadPromise = null
    }
  }

  const loadGuestChats = async () => {
    const stored = await hydrateGuestChatCache()
    const loadedConversations: Conversation[] = stored.map(item => ({
      session: item.session,
      messages: item.messages.map(message => ({
        ...message,
        createdAt: message.createdAt ? new Date(message.createdAt) : undefined
      })),
      status: 'ready',
      error: null,
      model: resolveAllowedModel(item.model, { local: true }),
      lastPrompt: item.lastPrompt,
      local: true,
      requestGeneration: 0
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
        model: defaultModelFor({ local: true }),
        lastPrompt: null,
        local: true,
        requestGeneration: 0
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
      model: defaultModelFor(),
      lastPrompt: null,
      requestGeneration: 0
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
      conversation.model = resolveAllowedModel(model, conversation)
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
    if (!conversation) return
    conversation.requestGeneration++
    if (conversation.activeRequest) {
      conversation.activeRequest.abort()
      conversation.activeRequest = undefined
      conversation.status = 'ready'
      const pendingAssistant = conversation.messages.at(-1)
      if (
        pendingAssistant?.role === 'assistant'
        && !getFirstTextContent(pendingAssistant)
      ) {
        clearMessageMetadata(pendingAssistant.id)
        conversation.messages.pop()
      }
      if (conversation.local) persistGuestConversations({ immediate: true })
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
    if (getFirstTextContent(message) || hasToolCalls(message.id)) {
      return
    }

    conversation.messages = conversation.messages.filter(existing => existing.id !== message.id)
    clearMessageMetadata(message.id)
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

  type StreamHandlerDeps = {
    conversation: Conversation
    assistantMessageIndex: number
    assistantMessage: UIMessage
    streamStatus: { receivedDone: boolean; receivedError: boolean; receivedContent: boolean }
    model: ModelOption
    isCurrent: () => boolean
  }

  const createStreamEventHandler = (
    deps: StreamHandlerDeps,
    onDone?: (messageId: string, event: StreamEvent) => void
  ) => {
    let currentMessage = deps.assistantMessage

    const handler = (event: StreamEvent) => {
      if (!deps.isCurrent()) return
      if (isToolCallStreamEvent(event)) {
        deps.conversation.status = 'streaming'
        ingestLiveToolCall(currentMessage.id, event)
        return
      }

      switch (event.type) {
        case 'text':
          if (event.content) {
            deps.streamStatus.receivedContent = true
            deps.conversation.status = 'streaming'
            currentMessage = appendTextToMessage(currentMessage, event.content)
            replaceMessageAtIndex(
              deps.conversation,
              deps.assistantMessageIndex,
              deps.assistantMessage.id,
              currentMessage
            )
          }
          break

        case 'reasoning':
          if (event.content) {
            deps.streamStatus.receivedContent = true
            deps.conversation.status = 'streaming'
            reasoningMap.value[currentMessage.id] =
              (reasoningMap.value[currentMessage.id] || '') + event.content
          }
          break

        case 'done':
          deps.streamStatus.receivedDone = true
          currentMessage = finalizeAssistantMessage(
            deps.conversation,
            deps.assistantMessageIndex,
            currentMessage,
            event
          )
          if (
            event.promptTokens != null ||
            event.generationTokens != null ||
            event.totalTokens != null
          ) {
            tokenUsageMap.value[currentMessage.id] = {
              promptTokens: event.promptTokens ?? null,
              generationTokens: event.generationTokens ?? null,
              totalTokens: event.totalTokens ?? null
            }
            messagePricingMap.value[currentMessage.id] = {
              promptPrice: deps.model.promptPrice ?? null,
              completionPrice: deps.model.completionPrice ?? null,
              requestPrice: deps.model.requestPrice ?? null
            }
          }
          onDone?.(currentMessage.id, event)
          break

        case 'error':
          deps.streamStatus.receivedError = true
          deps.conversation.error = event.content || 'Streaming error'
          break
      }
    }

    return { handler, getCurrentMessage: () => currentMessage }
  }

  const sendPrompt = async (
    chatId: string,
    text: string,
    model: ModelOption,
    options?: {
      appendUserMessage?: boolean
      media?: MediaAttachment[]
      documents?: Array<{ mimeType: string; name?: string }>
    }
  ): Promise<UIMessage | null> => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) {
      throw new Error('No chat selected')
    }

    const effectiveModel = resolveAllowedModel(model, conversation)
    if (!effectiveModel) return null

    if (!conversation.local) {
      const limit = AUTHENTICATED_PARALLEL_STREAM_LIMIT
      if (limit > 0) {
        const inFlight = conversations.value.filter(
          c =>
            !c.local
            && c.session.chatId !== chatId
            && (c.status === 'streaming' || c.status === 'submitted')
        ).length
        if (inFlight >= limit) {
          const { t } = useI18n()
          throw new Error(t('chat.parallelStreamsExceeded'))
        }
      }
    }

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
      if (options?.documents?.length) {
        for (const doc of options.documents) {
          parts.push({ type: 'file', mediaType: doc.mimeType, filename: doc.name, url: '' })
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
    const requestGeneration = ++conversation.requestGeneration
    const isCurrentRequest = () =>
      isCurrentConversationRequest(conversation, controller, requestGeneration)

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
        provider: isLegacyProvider(effectiveModel.provider) ? effectiveModel.provider : undefined,
        providerId: isLegacyProvider(effectiveModel.provider)
          ? undefined
          : effectiveModel.providerId || effectiveModel.provider,
        modelId: effectiveModel.value,
        connectionId: effectiveModel.connectionId || undefined,
        media: options?.media?.map(item => ({ mimeType: item.mimeType, data: item.data })),
        webSearchEnabled: webSearchEnabled.value || undefined
      }

      const { handler, getCurrentMessage } = createStreamEventHandler(
        {
          conversation,
          assistantMessageIndex,
          assistantMessage,
          streamStatus,
          model: effectiveModel,
          isCurrent: isCurrentRequest
        },
        (_messageId, event) => {
          if (event.chatTitle) {
            conversation.session.title = event.chatTitle
          } else if (conversation.local && !conversation.session.title) {
            conversation.session.title = text.length > 72 ? `${text.slice(0, 72).trim()}...` : text
          }
        }
      )

      if (conversation.local) {
        await streamAnonymousMessage(
          {
            content: input.content,
            provider: input.provider,
            modelId: input.modelId,
            media: input.media,
            history
          },
          handler,
          controller.signal
        )
      } else {
        await streamMessage(
          chatId,
          input,
          handler,
          controller.signal
        )
      }

      if (!isCurrentRequest()) return null
      conversation.activeRequest = undefined
      currentAssistantMessage = getCurrentMessage()
      const outcome = resolveStreamOutcome(streamStatus, conversation.error)
      conversation.status = outcome.status
      conversation.error = outcome.error
      if (conversation.local) {
        persistGuestConversations({ immediate: true })
      }

      if (outcome.status === 'error') {
        if (!getFirstTextContent(currentAssistantMessage)) {
          removeMessageIfEmpty(conversation, currentAssistantMessage)
          if (conversation.local) {
            persistGuestConversations({ immediate: true })
          }
          return null
        }
      }

      return currentAssistantMessage
    } catch (err) {
      if (!isCurrentRequest()) return null
      // Remove empty/partial assistant message on error
      removeMessageIfEmpty(conversation, currentAssistantMessage)

      if (controller.signal.aborted) {
        conversation.error = 'Request cancelled.'
      } else {
        conversation.error =
          err instanceof Error ? err.message : 'Something went wrong while chatting.'
      }
      conversation.status = 'error'
      if (conversation.activeRequest === controller) conversation.activeRequest = undefined
      if (conversation.local) {
        persistGuestConversations({ immediate: true })
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
    const requestGeneration = ++conversation.requestGeneration
    const isCurrentRequest = () =>
      isCurrentConversationRequest(conversation, controller, requestGeneration)

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
        provider: isLegacyProvider(model.provider) ? model.provider : undefined,
        providerId: isLegacyProvider(model.provider) ? undefined : model.providerId || model.provider,
        modelId: model.value,
        connectionId: model.connectionId || undefined,
        webSearchEnabled: webSearchEnabled.value || undefined
      }

      const { handler, getCurrentMessage } = createStreamEventHandler(
        {
          conversation,
          assistantMessageIndex,
          assistantMessage,
          streamStatus,
          model,
          isCurrent: isCurrentRequest
        }
      )

      await editAndStreamMessage(
        chatId,
        backendId,
        input,
        handler,
        controller.signal
      )

      if (!isCurrentRequest()) return null
      conversation.activeRequest = undefined
      currentAssistantMessage = getCurrentMessage()
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
      if (!isCurrentRequest()) return null
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
      if (conversation.activeRequest === controller) conversation.activeRequest = undefined
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
