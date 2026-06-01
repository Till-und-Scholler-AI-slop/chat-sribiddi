import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useToolCallStore } from '../useToolCallStore'

const apiMock = vi.hoisted(() => ({
  fetchMessageToolCalls: vi.fn()
}))

vi.mock('~/composables/useChatApi', () => ({
  useChatApi: () => apiMock
}))

function installNuxtState() {
  const state = new Map<string, ReturnType<typeof ref>>()
  vi.stubGlobal('useState', (key: string, init: () => unknown) => {
    if (!state.has(key)) {
      state.set(key, ref(init()))
    }
    return state.get(key)
  })
}

describe('useToolCallStore', () => {
  beforeEach(() => {
    installNuxtState()
    apiMock.fetchMessageToolCalls.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns an empty list when a message has no tool calls', async () => {
    const store = useToolCallStore()

    await expect(store.ensureToolCalls('msg-42')).resolves.toEqual([])

    expect(apiMock.fetchMessageToolCalls).not.toHaveBeenCalled()
    expect(store.getToolCalls('msg-42')).toEqual([])
  })

  it('loads and caches persisted tool call logs', async () => {
    const logs = [{
      id: 1,
      messageId: 42,
      toolName: 'calculator',
      inputPayload: '{}',
      outputPayload: '4'
    }]
    apiMock.fetchMessageToolCalls.mockResolvedValue(logs)

    const store = useToolCallStore()
    store.setToolCallAvailability('msg-42', true)

    await expect(store.ensureToolCalls('msg-42')).resolves.toEqual(logs)
    await expect(store.ensureToolCalls('msg-42')).resolves.toEqual(logs)

    expect(apiMock.fetchMessageToolCalls).toHaveBeenCalledTimes(1)
    expect(apiMock.fetchMessageToolCalls).toHaveBeenCalledWith(42)
    expect(store.isToolCallLoading('msg-42')).toBe(false)
    expect(store.getToolCallError('msg-42')).toBeNull()
  })

  it('merges live tool call events with persisted logs by client id', async () => {
    const persisted = [{
      id: 7,
      messageId: 42,
      clientId: 'live-1',
      status: 'SUCCESS' as const,
      toolName: 'ImageGenerationTool.generateImage',
      inputPayload: '{"prompt":"city"}',
      outputPayload: '{"type":"image","url":"https://example.com/image.png"}',
      displayType: 'image'
    }]
    apiMock.fetchMessageToolCalls.mockResolvedValue(persisted)

    const store = useToolCallStore()
    store.applyToolCallEvent('msg-42', {
      type: 'tool_call_started',
      clientId: 'live-1',
      toolName: 'ImageGenerationTool.generateImage',
      toolCallStatus: 'RUNNING',
      inputPayload: '{"prompt":"city"}',
      displayType: 'image'
    })

    expect(store.hasToolCalls('msg-42')).toBe(true)
    expect(store.getToolCalls('msg-42')).toEqual([
      expect.objectContaining({ clientId: 'live-1', status: 'RUNNING' })
    ])

    await store.ensureToolCalls('msg-42')

    expect(store.getToolCalls('msg-42')).toEqual(persisted)
  })

  it('rekeys tool-call metadata when a streamed message receives a backend id', async () => {
    const logs = [{
      messageId: 42,
      toolName: 'calculator',
      inputPayload: '{}'
    }]

    const store = useToolCallStore()
    store.setToolCallAvailability('tmp-message', true)
    store.toolCallLogMap.value['tmp-message'] = logs

    store.rekeyToolCallMetadata('tmp-message', 'msg-42')

    expect(store.hasToolCalls('msg-42')).toBe(true)
    expect(store.getToolCalls('msg-42')).toEqual(logs)
    expect(store.hasToolCalls('tmp-message')).toBe(false)
  })

  it('rejects tool-call loading for temporary message ids', async () => {
    const store = useToolCallStore()
    store.setToolCallAvailability('tmp-message', true)

    await expect(store.ensureToolCalls('tmp-message')).rejects.toThrow(
      'persisted messages'
    )
  })
})
