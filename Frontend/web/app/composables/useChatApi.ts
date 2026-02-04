export type Provider = 'OPENAI' | 'GOOGLE'

export type ModelOption = {
  label: string
  value: string
  provider: Provider
}

export type ChatSession = {
  chatId: string
  title?: string | null
  createdAt?: string | null
  userId?: string | null
}

type CreateChatInput = {
  title?: string
}

type SendMessageInput = {
  content: string
  provider: Provider
  modelId: string
}

const DEFAULT_BACKEND_BASE_URL = 'http://localhost:8080'

function normalizeBaseUrl(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value
}

async function readErrorText(response: Response) {
  try {
    const text = await response.text()
    return text.trim()
  } catch {
    return ''
  }
}

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
    const response = await fetch(buildUrl(path), {
      credentials: 'include',
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers || {})
      }
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

  const sendMessage = async (
    chatId: string,
    input: SendMessageInput,
    signal?: AbortSignal
  ) => {
    return requestJson<unknown>(`/chats/${chatId}/messages`, {
      method: 'POST',
      body: JSON.stringify(input),
      signal
    })
  }

  const fetchModels = async (provider: Provider) => {
    const path = provider === 'OPENAI' ? '/models/openai' : '/models/google'
    const models = await requestJson<Array<{ id: string; label: string }>>(path, {
      method: 'GET'
    })

    return models.map(model => ({
      label: model.label,
      value: model.id,
      provider
    }))
  }

  return {
    baseUrl,
    resolveUserId,
    createChat,
    sendMessage,
    fetchModels
  }
}
