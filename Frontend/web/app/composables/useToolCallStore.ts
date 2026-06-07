import type { StreamEvent } from '~/composables/chat-stream'
import type { ToolCallLog } from '~/composables/useChatApi'
import { useChatApi } from '~/composables/useChatApi'
import { parsePersistedMessageId } from '~/composables/chat-helpers'
import { rekeyStateEntry } from '~/utils/model-utils'

type LiveToolCallEvent = StreamEvent & {
  type: 'tool_call_started' | 'tool_call_completed' | 'tool_call_failed'
}

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

    const backendId = parsePersistedMessageId(newId)
    if (backendId != null && toolCallLogMap.value[newId]) {
      toolCallLogMap.value[newId] = toolCallLogMap.value[newId].map(log => ({
        ...log,
        messageId: backendId
      }))
    }
  }

  const setToolCallAvailability = (messageId: string, available: boolean) => {
    toolCallAvailabilityMap.value[messageId] = available
  }

  const hasToolCalls = (messageId: string) => toolCallAvailabilityMap.value[messageId] === true

  const getToolCalls = (messageId: string) => toolCallLogMap.value[messageId] ?? []

  const getToolCallError = (messageId: string) => toolCallErrorMap.value[messageId] ?? null

  const isToolCallLoading = (messageId: string) => toolCallLoadingMap.value[messageId] === true

  const ingestLiveToolCall = (messageId: string, event: LiveToolCallEvent) => {
    if (!event.toolName?.trim()) return

    const backendId = parsePersistedMessageId(messageId) ?? event.messageId ?? 0
    const log: ToolCallLog = {
      messageId: backendId,
      clientId: event.clientId ?? null,
      toolName: event.toolName,
      inputPayload: event.inputPayload ?? '{}',
      outputPayload: event.outputPayload ?? null,
      startedAt: event.startedAt ?? new Date().toISOString(),
      durationMs: event.durationMs ?? null,
      contextExcerpt: event.contextExcerpt ?? null,
      status: event.status ?? (event.type === 'tool_call_started' ? 'RUNNING' : event.type === 'tool_call_completed' ? 'SUCCESS' : 'ERROR'),
      errorMessage: event.errorMessage ?? null,
      displayType: event.displayType ?? null
    }

    const current = toolCallLogMap.value[messageId] ?? []
    toolCallLogMap.value[messageId] = mergeToolCallLogs(current, [log])
    toolCallAvailabilityMap.value[messageId] = true
    toolCallLoadingMap.value[messageId] = toolCallLogMap.value[messageId].some(
      item => item.status === 'RUNNING'
    )
  }

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
      const merged = mergeToolCallLogs(toolCallLogMap.value[messageId] ?? [], logs)
      toolCallLogMap.value[messageId] = merged
      toolCallLoadingMap.value[messageId] = merged.some(item => item.status === 'RUNNING')
      return merged
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
    ingestLiveToolCall,
    hasToolCalls,
    getToolCalls,
    getToolCallError,
    isToolCallLoading,
    ensureToolCalls
  }
}

function mergeToolCallLogs(current: ToolCallLog[], incoming: ToolCallLog[]) {
  const merged = [...current]
  for (const log of incoming) {
    const index = merged.findIndex(existing => toolCallKey(existing) === toolCallKey(log))
    if (index === -1) {
      merged.push(log)
    } else {
      const existing = merged[index]
      if (!existing) continue
      merged[index] = {
        ...existing,
        ...log,
        inputPayload: log.inputPayload || existing.inputPayload,
        outputPayload: log.outputPayload ?? existing.outputPayload ?? null,
        contextExcerpt: log.contextExcerpt ?? existing.contextExcerpt ?? null,
        errorMessage: log.errorMessage ?? existing.errorMessage ?? null,
        displayType: log.displayType ?? existing.displayType ?? null
      }
    }
  }
  return merged.sort((a, b) => {
    const aTime = a.startedAt ? Date.parse(a.startedAt) : 0
    const bTime = b.startedAt ? Date.parse(b.startedAt) : 0
    return aTime - bTime
  })
}

function toolCallKey(log: ToolCallLog) {
  if (log.clientId) return `client:${log.clientId}`
  if (log.id != null) return `id:${log.id}`
  return `fallback:${log.toolName}:${log.startedAt ?? ''}`
}
