import { DEFAULT_BACKEND_BASE_URL, getCsrfToken, normalizeBaseUrl } from './useChatApi'

export type AvailableTool = {
  name: string
  displayName: string
  description: string
  enabled: boolean
}

export type ChatToolConfig = {
  id: number
  toolName: string
  enabled: boolean
  createdAt: string
}

export function useToolApi() {
  const { resolveUserId } = useChatApi()
  const { settings } = useUserSettings()
  const config = useRuntimeConfig()

  const baseUrl = computed(() => {
    const configured =
      typeof config.public.backendBaseUrl === 'string'
        ? config.public.backendBaseUrl.trim()
        : ''
    return normalizeBaseUrl(
      settings.value.backendBaseUrl?.trim() || configured || DEFAULT_BACKEND_BASE_URL
    )
  })

  const headers = (mutating = false) => {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Chat-User-Id': resolveUserId()
    }
    if (mutating) {
      const csrf = getCsrfToken()
      if (csrf) h['X-XSRF-TOKEN'] = csrf
    }
    return h
  }

  const fetchAvailableTools = async (chatId: string, signal?: AbortSignal): Promise<AvailableTool[]> => {
    const res = await fetch(`${baseUrl.value}/chats/${chatId}/tools/available`, {
      headers: headers(),
      credentials: 'include',
      signal
    })
    if (!res.ok) throw new Error('Failed to fetch available tools')
    return res.json()
  }

  const setToolEnabled = async (chatId: string, toolName: string, enabled: boolean): Promise<ChatToolConfig> => {
    const res = await fetch(`${baseUrl.value}/chats/${chatId}/tools`, {
      method: 'PUT',
      headers: headers(true),
      credentials: 'include',
      body: JSON.stringify({ toolName, enabled })
    })
    if (!res.ok) throw new Error('Failed to update tool')
    return res.json()
  }

  const removeTool = async (chatId: string, toolName: string) => {
    const res = await fetch(`${baseUrl.value}/chats/${chatId}/tools/${toolName}`, {
      method: 'DELETE',
      headers: headers(true),
      credentials: 'include'
    })
    if (!res.ok) throw new Error(`Failed to remove tool: ${res.status} ${res.statusText}`)
  }

  return {
    fetchAvailableTools,
    setToolEnabled,
    removeTool
  }
}
