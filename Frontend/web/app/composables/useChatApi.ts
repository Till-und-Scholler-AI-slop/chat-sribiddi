export type Provider = 'OPENAI' | 'GOOGLE' | 'CLAUDE'

export type ModelOption = {
  label: string
  value: string
  provider: Provider
}

export type MediaAttachment = {
  mimeType: string
  data: string
  name?: string
}

export type ChatSession = {
  chatId: string
  title?: string | null
  createdAt?: string | null
  userId?: string | null
}

export type BackendMessage = {
  id?: number | null
  chatId: string
  message: string
  context?: string | null
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  createdAt?: string | null
  promptTokens?: number | null
  generationTokens?: number | null
  totalTokens?: number | null
}

export type TokenUsage = {
  promptTokens: number | null
  generationTokens: number | null
  totalTokens: number | null
}

export type SendMessageResponse = {
  message: string
  promptTokens?: number | null
  generationTokens?: number | null
  totalTokens?: number | null
}

type CreateChatInput = {
  title?: string
}

type UpdateChatInput = {
  title: string
}

type SendMessageInput = {
  content: string
  provider: Provider
  modelId: string
  media?: Array<Pick<MediaAttachment, 'mimeType' | 'data'>>
}

export const DEFAULT_BACKEND_BASE_URL = 'http://localhost:8080'

export function normalizeBaseUrl(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value
}

export async function readErrorText(response: Response) {
  try {
    const text = await response.text()
    return text.trim()
  } catch {
    return ''
  }
}

function getCsrfToken(): string | null {
  if (typeof document === 'undefined') return null
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export function useChatApi() {
  const config = useRuntimeConfig()
  const { settings } = useUserSettings()
  const configuredBaseUrl =
    typeof config.public.backendBaseUrl === 'string'
      ? config.public.backendBaseUrl.trim()
      : ''
  const baseUrl = computed(() =>
    normalizeBaseUrl(
      settings.value.backendBaseUrl?.trim() ||
        configuredBaseUrl ||
        DEFAULT_BACKEND_BASE_URL
    )
  )

  const userIdState = useState<string | null>('chat-user-id', () => null)

  const resolveUserId = () => {
    if (userIdState.value && userIdState.value.trim() !== '') {
      return userIdState.value
    }

    const configuredUserId =
      typeof config.public.userId === 'string' ? config.public.userId.trim() : ''
    if (configuredUserId) {
      userIdState.value = configuredUserId
      return configuredUserId
    }

    if (import.meta.client) {
      const stored = localStorage.getItem('chat-user-id')
      if (stored && stored.trim() !== '') {
        userIdState.value = stored
        return stored
      }

      const generated =
        globalThis.crypto?.randomUUID?.() ||
        `user-${Date.now()}-${Math.random().toString(16).slice(2)}`
      localStorage.setItem('chat-user-id', generated)
      userIdState.value = generated
      return generated
    }

    userIdState.value = 'anonymous'
    return 'anonymous'
  }

  const buildUrl = (path: string) => {
    const normalized = path.startsWith('/') ? path : `/${path}`
    return `${baseUrl.value}${normalized}`
  }

  const requestJson = async <T>(path: string, options: RequestInit) => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string> || {})
    }

    const method = (options.method || 'GET').toUpperCase()
    if (MUTATING_METHODS.has(method)) {
      const csrfToken = getCsrfToken()
      if (csrfToken) {
        headers['X-XSRF-TOKEN'] = csrfToken
      }
    }

    const response = await fetch(buildUrl(path), {
      credentials: 'include',
      ...options,
      headers
    })

    if (!response.ok) {
      if (response.status === 401) {
        throw new Error('Not authenticated. Please log in via the Login page.')
      }
      const errorText = await readErrorText(response)
      throw new Error(
        errorText || `Request failed (${response.status} ${response.statusText})`
      )
    }

    if (response.status === 204) {
      return null as T
    }

    return (await response.json()) as T
  }

  const createChat = async (input: CreateChatInput = {}) => {
    const title = input.title?.trim()
    const payload: Record<string, string> = {
      userId: resolveUserId()
    }
    if (title) {
      payload.title = title
    }

    return requestJson<ChatSession>('/chats', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  }

  const fetchChats = async () => {
    return requestJson<ChatSession[]>('/chats', { method: 'GET' })
  }

  const fetchChatMessages = async (chatId: string) => {
    return requestJson<BackendMessage[]>(`/chats/${chatId}/messages`, { method: 'GET' })
  }

  const sendMessage = async (
    chatId: string,
    input: SendMessageInput,
    signal?: AbortSignal
  ) => {
    return requestJson<SendMessageResponse>(`/chats/${chatId}/messages`, {
      method: 'POST',
      body: JSON.stringify(input),
      signal
    })
  }

  const updateChat = async (chatId: string, input: UpdateChatInput) => {
    return requestJson<ChatSession>(`/chats/${chatId}`, {
      method: 'PATCH',
      body: JSON.stringify({ title: input.title })
    })
  }

  const deleteChat = async (chatId: string) => {
    await requestJson<void>(`/chats/${chatId}`, { method: 'DELETE' })
  }

  const fetchModels = async (provider: Provider) => {
    const path =
      provider === 'OPENAI'
        ? '/models/openai'
        : provider === 'GOOGLE'
          ? '/models/google'
          : '/models/claude'
    const data = await requestJson<Array<{ id: string; label: string }>>(path, {
      method: 'GET'
    })

    if (!data) return []

    return data.map(model => ({
      label: model.label,
      value: model.id,
      provider
    }))
  }

  return {
    baseUrl,
    requestJson,
    resolveUserId,
    createChat,
    fetchChats,
    fetchChatMessages,
    sendMessage,
    updateChat,
    deleteChat,
    fetchModels
  }
}
