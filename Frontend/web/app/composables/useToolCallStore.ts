import type { ToolCallLog } from '~/composables/useChatApi'
import { useChatApi } from '~/composables/useChatApi'
import type { StreamEvent } from '~/composables/chat-stream'
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

  const applyToolCallEvent = (messageId: string, event: StreamEvent) => {
    if (!event.toolName || !event.clientId) return
    const current = toolCallLogMap.value[messageId] ?? []
    const nextLog: ToolCallLog = {
      messageId: parsePersistedMessageId(messageId),
      clientId: event.clientId,
      toolName: event.toolName,
      status: event.toolCallStatus ?? statusFromEventType(event.type),
      inputPayload: event.inputPayload ?? '{}',
      outputPayload: event.outputPayload ?? null,
      errorMessage: event.errorMessage ?? null,
      startedAt: event.startedAt ?? new Date().toISOString(),
      durationMs: event.durationMs ?? null,
      contextExcerpt: null,
      displayType: event.displayType ?? null
    }
    const index = current.findIndex(log => log.clientId === event.clientId)
    toolCallLogMap.value[messageId] =
      index >= 0
        ? current.map((log, currentIndex) => currentIndex === index ? { ...log, ...nextLog } : log)
        : [...current, nextLog]
    toolCallAvailabilityMap.value[messageId] = true
  }

  const ensureToolCalls = async (messageId: string) => {
    if (!hasToolCalls(messageId)) {
      toolCallLogMap.value[messageId] = []
      return []
    }

    const existing = toolCallLogMap.value[messageId]

    const backendId = parsePersistedMessageId(messageId)
    if (existing && (backendId == null || existing.every(log => log.id != null))) {
      return existing
    }
    if (backendId == null) {
      throw new Error('Tool call logs are only available for persisted messages.')
    }

    toolCallLoadingMap.value[messageId] = true
    toolCallErrorMap.value[messageId] = null

    try {
      const logs = await fetchMessageToolCalls(backendId)
      toolCallLogMap.value[messageId] = mergePersistedToolCalls(
        toolCallLogMap.value[messageId] ?? [],
        logs
      )
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
    applyToolCallEvent,
    ensureToolCalls
  }
}

function statusFromEventType(type: StreamEvent['type']): ToolCallLog['status'] {
  if (type === 'tool_call_started') return 'RUNNING'
  if (type === 'tool_call_failed') return 'FAILED'
  if (type === 'tool_call_completed') return 'SUCCESS'
  return null
}

function mergePersistedToolCalls(live: ToolCallLog[], persisted: ToolCallLog[]) {
  if (live.length === 0) return persisted
  const byClientId = new Map(live.map(log => [log.clientId, log]))
  for (const log of persisted) {
    if (log.clientId) {
      byClientId.set(log.clientId, log)
    }
  }
  const persistedWithoutClientId = persisted.filter(log => !log.clientId)
  return [...byClientId.values(), ...persistedWithoutClientId]
}
