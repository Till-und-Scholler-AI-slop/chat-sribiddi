export type AvailableTool = {
  name: string
  displayName: string
  description: string
  enabled: boolean
}

export function useToolApi() {
  const { requestJson } = useChatApi()

  const fetchAvailableTools = async (chatId: string, signal?: AbortSignal): Promise<AvailableTool[]> => {
    return requestJson<AvailableTool[]>(`/chats/${chatId}/tools/available`, { method: 'GET', signal }) ?? []
  }

  return {
    fetchAvailableTools
  }
}
