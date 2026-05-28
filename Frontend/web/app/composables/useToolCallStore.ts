import type { ToolCallLog } from '~/composables/useChatApi'
import { useChatApi } from '~/composables/useChatApi'
import { parsePersistedMessageId } from '~/composables/chat-helpers'
import { rekeyStateEntry } from '~/utils/model-utils'

export function useToolCallStore() {
  const { fetchMessageToolCalls } = useChatApi()

  const toolCallAvailabilityMap = useState<Record<string, boolean>>('chat-tool-call-availability', () => ({}))
  const toolCallLogMap = useState<Record<string, ToolCallLog[]>>('chat-tool-call-logs', () => ({}))
  const toolCallLoadingMap = useState<Record<string, boolean>>('chat-tool-call-loading', () => ({}))
  const toolCallErrorMap = useState<Record<string, string | null>>('chat-tool-call-errors', () => ({}))

  const clearToolCallMetadata = (messageId: string) => {
    Reflect.deleteProperty(toolCallAvailabilityMap.value, messageId)
    Reflect.deleteProperty(toolCallLogMap.value, messageId)
    Reflect.deleteProperty(toolCallLoadingMap.value, messageId)
    Reflect.deleteProperty(toolCallErrorMap.value, messageId)
  }

  const resetToolCallState = () => {
    toolCallAvailabilityMap.value = {}
    toolCallLogMap.value = {}
    toolCallLoadingMap.value = {}
    toolCallErrorMap.value = {}
  }

  const rekeyToolCallMetadata = (oldId: string, newId: string) => {
    rekeyStateEntry(toolCallAvailabilityMap.value, oldId, newId)
    rekeyStateEntry(toolCallLogMap.value, oldId, newId)
    rekeyStateEntry(toolCallLoadingMap.value, oldId, newId)
    rekeyStateEntry(toolCallErrorMap.value, oldId, newId)
  }

  const setToolCallAvailability = (messageId: string, available: boolean) => {
    toolCallAvailabilityMap.value[messageId] = available
  }

  const hasToolCalls = (messageId: string) => toolCallAvailabilityMap.value[messageId] === true

  const getToolCalls = (messageId: string) => toolCallLogMap.value[messageId] ?? []

  const getToolCallError = (messageId: string) => toolCallErrorMap.value[messageId] ?? null

  const isToolCallLoading = (messageId: string) => toolCallLoadingMap.value[messageId] === true

  const ensureToolCalls = async (messageId: string) => {
    if (!hasToolCalls(messageId)) {
      toolCallLogMap.value[messageId] = []
      return []
    }

    if (toolCallLogMap.value[messageId]) {
      return toolCallLogMap.value[messageId]
    }

    const backendId = parsePersistedMessageId(messageId)
    if (backendId == null) {
      throw new Error('Tool call logs are only available for persisted messages.')
    }

    toolCallLoadingMap.value[messageId] = true
    toolCallErrorMap.value[messageId] = null

    try {
      const logs = await fetchMessageToolCalls(backendId)
      toolCallLogMap.value[messageId] = logs
      return logs
    } catch (err) {
      toolCallErrorMap.value[messageId] =
        err instanceof Error ? err.message : 'Unable to load tool call logs.'
      throw err
    } finally {
      toolCallLoadingMap.value[messageId] = false
    }
  }

  return {
    toolCallAvailabilityMap,
    toolCallLogMap,
    toolCallLoadingMap,
    toolCallErrorMap,
    clearToolCallMetadata,
    resetToolCallState,
    rekeyToolCallMetadata,
    setToolCallAvailability,
    hasToolCalls,
    getToolCalls,
    getToolCallError,
    isToolCallLoading,
    ensureToolCalls
  }
}
