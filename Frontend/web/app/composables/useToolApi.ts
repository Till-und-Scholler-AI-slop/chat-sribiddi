import type { ComputedRef } from 'vue'

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
  const config = useRuntimeConfig()
  const { settings } = useUserSettings()

  const baseUrl = computed(() => {
    const configured =
      typeof config.public.backendBaseUrl === 'string'
        ? config.public.backendBaseUrl.trim()
        : ''
    const base =
      settings.value.backendBaseUrl?.trim() || configured || 'http://localhost:8080'
    return base.endsWith('/') ? base.slice(0, -1) : base
  })

  const getUserId = () => {
    if (import.meta.client) {
      return localStorage.getItem('chat-user-id') || 'anonymous'
    }
    return 'anonymous'
  }

  const headers = () => ({
    'Content-Type': 'application/json',
    'X-Chat-User-Id': getUserId()
  })

  const fetchAvailableTools = async (chatId: string): Promise<AvailableTool[]> => {
    const res = await fetch(`${baseUrl.value}/api/chats/${chatId}/tools/available`, {
      headers: headers(),
      credentials: 'include'
    })
    if (!res.ok) throw new Error('Failed to fetch available tools')
    return res.json()
  }

  const setToolEnabled = async (chatId: string, toolName: string, enabled: boolean): Promise<ChatToolConfig> => {
    const res = await fetch(`${baseUrl.value}/api/chats/${chatId}/tools`, {
      method: 'PUT',
      headers: headers(),
      credentials: 'include',
      body: JSON.stringify({ toolName, enabled })
    })
    if (!res.ok) throw new Error('Failed to update tool')
    return res.json()
  }

  const removeTool = async (chatId: string, toolName: string) => {
    const res = await fetch(`${baseUrl.value}/api/chats/${chatId}/tools/${toolName}`, {
      method: 'DELETE',
      headers: headers(),
      credentials: 'include'
    })
    if (!res.ok && res.status !== 204) throw new Error('Failed to remove tool')
  }

  return {
    fetchAvailableTools,
    setToolEnabled,
    removeTool
  }
}
