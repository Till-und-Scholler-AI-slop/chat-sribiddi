export const PROVIDERS = ['OPENAI', 'GOOGLE', 'CLAUDE', 'OPENROUTER'] as const

export type Provider = (typeof PROVIDERS)[number]

export const MODEL_ENDPOINTS: Record<Provider, string> = {
  OPENAI: '/models/openai',
  GOOGLE: '/models/google',
  CLAUDE: '/models/claude',
  OPENROUTER: '/models/openrouter'
}

export const REQUEST_USER_ID_HEADER = 'X-Chat-User-Id'

export type ModelOption = {
  label: string
  value: string
  provider: Provider
  canonicalSlug?: string | null
  created?: number | null
  description?: string | null
  contextLength?: number | null
  maxCompletionTokens?: number | null
  promptPrice?: string | null
  completionPrice?: string | null
  requestPrice?: string | null
  imagePrice?: string | null
  modality?: string | null
  inputModalities?: string[]
  outputModalities?: string[]
  supportedParameters?: string[]
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

export type StreamEvent = {
  type: 'text' | 'reasoning' | 'done' | 'error'
  content?: string | null
  promptTokens?: number | null
  generationTokens?: number | null
  totalTokens?: number | null
  chatTitle?: string | null
}

/**
 * Parses a raw SSE chunk into individual stream events.
 * Each SSE message is delimited by a blank line and may contain
 * `event:` and `data:` fields.
 */
export function parseSseEvents(raw: string): StreamEvent[] {
  const events: StreamEvent[] = []
  const blocks = raw.split(/\n\n+/)

  for (const block of blocks) {
    const trimmedBlock = block.trim()
    if (!trimmedBlock) continue

    let dataLine = ''
    for (const line of trimmedBlock.split('\n')) {
      if (line.startsWith('data:')) {
        dataLine = line.slice(5).trim()
      }
    }

    if (!dataLine) continue

    try {
      const parsed = JSON.parse(dataLine) as StreamEvent
      if (parsed && parsed.type) {
        events.push(parsed)
      }
    } catch {
      // skip malformed JSON
    }
  }

  return events
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
  webSearchEnabled?: boolean
}

export const DEFAULT_BACKEND_BASE_URL = 'http://localhost:8080'

export function normalizeBaseUrl(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value
}

export function modelEndpointPath(provider: Provider) {
  return MODEL_ENDPOINTS[provider]
}

export function withRequestUserId(
  headers: Record<string, string>,
  userId?: string | null
) {
  if (headers[REQUEST_USER_ID_HEADER]) {
    return headers
  }

  const normalizedUserId = userId?.trim()
  if (!normalizedUserId) {
    return headers
  }

  return {
    ...headers,
    [REQUEST_USER_ID_HEADER]: normalizedUserId
  }
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
    let headers: Record<string, string> = {
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

    if (import.meta.client) {
      headers = withRequestUserId(headers, resolveUserId())
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

  const fetchChatMessages = async (chatId: string, limit?: number) => {
    const params = limit ? `?limit=${limit}` : ''
    return requestJson<BackendMessage[]>(`/chats/${chatId}/messages${params}`, { method: 'GET' })
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

  /**
   * Shared helper: performs a POST to `url`, then reads the SSE stream and
   * calls `onEvent` for every parsed {@link StreamEvent}.
   */
  const fetchSseStream = async (
    url: string,
    body: string,
    onEvent: (event: StreamEvent) => void,
    signal?: AbortSignal
  ) => {
    let headers: Record<string, string> = {
      'Content-Type': 'application/json'
    }

    const csrfToken = getCsrfToken()
    if (csrfToken) {
      headers['X-XSRF-TOKEN'] = csrfToken
    }

    if (import.meta.client) {
      headers = withRequestUserId(headers, resolveUserId())
    }

    const response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers,
      body,
      signal
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

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('Response body is not readable')
    }

    const decoder = new TextDecoder()
    let buffer = ''

    try {
      let searchFrom = 0
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // Process complete SSE events (delimited by double newline)
        let idx: number
        while ((idx = buffer.indexOf('\n\n', searchFrom)) !== -1) {
          const part = buffer.substring(0, idx)
          buffer = buffer.substring(idx + 2)
          searchFrom = 0

          const events = parseSseEvents(part + '\n\n')
          for (const event of events) {
            onEvent(event)
          }
        }
        searchFrom = Math.max(0, buffer.length - 1)
      }

      // Process any remaining buffer
      if (buffer.trim()) {
        const events = parseSseEvents(buffer + '\n\n')
        for (const event of events) {
          onEvent(event)
        }
      }
    } finally {
      reader.releaseLock()
    }
  }

  /**
   * Streams a chat response via Server-Sent Events.
   * Calls `onEvent` for each parsed {@link StreamEvent}.
   */
  const streamMessage = async (
    chatId: string,
    input: SendMessageInput,
    onEvent: (event: StreamEvent) => void,
    signal?: AbortSignal
  ) => {
    return fetchSseStream(
      buildUrl(`/chats/${chatId}/messages/stream`),
      JSON.stringify(input),
      onEvent,
      signal
    )
  }

  /**
   * Edits an existing user message and streams a new AI response.
   * All messages after the edited message are deleted on the backend before streaming.
   */
  const editAndStreamMessage = async (
    chatId: string,
    messageId: number,
    input: SendMessageInput,
    onEvent: (event: StreamEvent) => void,
    signal?: AbortSignal
  ) => {
    return fetchSseStream(
      buildUrl(`/chats/${chatId}/messages/${messageId}/stream`),
      JSON.stringify(input),
      onEvent,
      signal
    )
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

  const deleteAccount = async () => {
    await requestJson<void>('/account', { method: 'DELETE' })
  }

  const fetchModels = async (provider: Provider) => {
    const data = await requestJson<Array<{
      id: string
      label: string
      canonicalSlug?: string | null
      created?: number | null
      description?: string | null
      contextLength?: number | null
      maxCompletionTokens?: number | null
      promptPrice?: string | null
      completionPrice?: string | null
      requestPrice?: string | null
      imagePrice?: string | null
      modality?: string | null
      inputModalities?: string[] | null
      outputModalities?: string[] | null
      supportedParameters?: string[] | null
    }>>(modelEndpointPath(provider), {
      method: 'GET'
    })

    if (!data) return []

    return data.map(model => ({
      label: model.label,
      value: model.id,
      provider,
      canonicalSlug: model.canonicalSlug ?? null,
      created: model.created ?? null,
      description: model.description ?? null,
      contextLength: model.contextLength ?? null,
      maxCompletionTokens: model.maxCompletionTokens ?? null,
      promptPrice: model.promptPrice ?? null,
      completionPrice: model.completionPrice ?? null,
      requestPrice: model.requestPrice ?? null,
      imagePrice: model.imagePrice ?? null,
      modality: model.modality ?? null,
      inputModalities: model.inputModalities ?? [],
      outputModalities: model.outputModalities ?? [],
      supportedParameters: model.supportedParameters ?? []
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
    streamMessage,
    editAndStreamMessage,
    updateChat,
    deleteChat,
    deleteAccount,
    fetchModels
  }
}
