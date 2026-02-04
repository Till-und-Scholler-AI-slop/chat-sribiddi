import type { ChatStatus, UIMessage } from 'ai'
import type { ChatSession, ModelOption } from '~/composables/useChatApi'
import { useChatApi } from '~/composables/useChatApi'
import { useUserSettings } from '~/composables/useUserSettings'

type Conversation = {
  session: ChatSession
  messages: UIMessage[]
  status: ChatStatus
  error: string | null
  activeRequest?: AbortController
  model?: ModelOption | null
}

const messageId = () =>
  globalThis.crypto?.randomUUID?.() ||
  `msg-${Date.now()}-${Math.random().toString(16).slice(2)}`

function extractAssistantText(response: any): string {
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

export function useChatStore() {
  const { createChat, sendMessage, fetchModels } = useChatApi()
  const { settings } = useUserSettings()

  const conversations = useState<Conversation[]>('chat-conversations', () => [])
  const activeChatId = useState<string | null>('chat-active-id', () => null)
  const models = useState<ModelOption[]>('chat-models', () => [])
  const modelsLoading = useState<boolean>('chat-models-loading', () => false)
  const modelsError = useState<string | null>('chat-models-error', () => null)

  const activeConversation = computed(() =>
    conversations.value.find(c => c.session.chatId === activeChatId.value) || null
  )

  const defaultModel = computed<ModelOption | null>(() => models.value[0] || null)

  watch(
    () => settings.value.backendBaseUrl,
    () => {
      // Reset cached data when the backend changes
      models.value = []
      conversations.value = []
      activeChatId.value = null
    }
  )

  const ensureModels = async () => {
    if (models.value.length > 0) {
      return models.value
    }

    modelsLoading.value = true
    try {
      const [openAiModels, googleModels] = await Promise.all([
        fetchModels('OPENAI'),
        fetchModels('GOOGLE')
      ])
      models.value = [...openAiModels, ...googleModels]
      modelsError.value = null
    } catch (err) {
      console.error(err)
      modelsError.value =
        err instanceof Error ? err.message : 'Unable to load models from backend.'
    } finally {
      modelsLoading.value = false
    }

    return models.value
  }

  const startChat = async (title?: string) => {
    const session = await createChat(title ? { title } : {})
    const conversation: Conversation = {
      session,
      messages: [],
      status: 'ready',
      error: null,
      model: defaultModel.value
    }

    conversations.value = [
      conversation,
      ...conversations.value.filter(c => c.session.chatId !== session.chatId)
    ]
    activeChatId.value = session.chatId
    return session
  }

  const selectChat = (chatId: string) => {
    activeChatId.value = chatId
  }

  const setModelForChat = (chatId: string, model: ModelOption) => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (conversation) {
      conversation.model = model
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
    model: ModelOption
  ): Promise<UIMessage | null> => {
    const conversation = conversations.value.find(c => c.session.chatId === chatId)
    if (!conversation) {
      throw new Error('No chat selected')
    }

    stop(chatId) // cancel any ongoing request

    const userMessage: UIMessage = {
      id: messageId(),
      role: 'user',
      parts: [{ type: 'text', text }]
    }
    conversation.messages.push(userMessage)
    conversation.status = 'submitted'
    conversation.error = null
    conversation.model = model

    const controller = new AbortController()
    conversation.activeRequest = controller

    try {
      const response = await sendMessage(
        chatId,
        {
          content: text,
          provider: model.provider,
          modelId: model.value
        },
        controller.signal
      )

      const assistantMessage: UIMessage = {
        id: messageId(),
        role: 'assistant',
        parts: [{ type: 'text', text: extractAssistantText(response) }]
      }

      conversation.messages.push(assistantMessage)
      conversation.status = 'ready'
      conversation.activeRequest = undefined

      return assistantMessage
    } catch (err) {
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
    modelsLoading,
    modelsError,
    defaultModel,
    ensureModels,
    startChat,
    selectChat,
    setModelForChat,
    sendPrompt,
    stop
  }
}
