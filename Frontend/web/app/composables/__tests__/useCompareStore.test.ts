import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import type { StreamEvent } from '../chat-stream'
import type { ModelOption, Provider } from '../useChatApi'
import { useCompareStore } from '../useCompareStore'

const apiMock = vi.hoisted(() => ({
  createChat: vi.fn(),
  deleteChat: vi.fn(),
  fetchModels: vi.fn(),
  streamMessage: vi.fn()
}))

vi.mock('~/composables/useChatApi', () => ({
  PROVIDERS: ['OPENAI', 'GOOGLE', 'CLAUDE', 'OPENROUTER'],
  isModelLocked: (model: { availability?: string | null }) => model.availability === 'UPGRADE_REQUIRED',
  useChatApi: () => apiMock
}))

const makeModel = (provider: Provider, value: string, label: string): ModelOption => ({
  provider,
  value,
  label,
  promptPrice: '0.000001',
  completionPrice: '0.000002',
  requestPrice: null,
  availability: 'ALWAYS'
})

function installNuxtState() {
  const state = new Map<string, ReturnType<typeof ref>>()
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('useState', (key: string, init: () => unknown) => {
    if (!state.has(key)) {
      state.set(key, ref(init()))
    }
    return state.get(key)
  })
}

describe('useCompareStore', () => {
  const openAiModel = makeModel('OPENAI', 'gpt-test', 'GPT Test')
  const googleModel = makeModel('GOOGLE', 'gemini-test', 'Gemini Test')

  beforeEach(() => {
    installNuxtState()
    apiMock.createChat.mockReset()
    apiMock.deleteChat.mockReset()
    apiMock.fetchModels.mockReset()
    apiMock.streamMessage.mockReset()
    apiMock.deleteChat.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads models and assigns the default comparison panels', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'GOOGLE') return [googleModel]
      return []
    })

    const store = useCompareStore()
    await store.ensureModels()

    expect(store.sortedModels.value).toEqual([openAiModel, googleModel])
    expect(store.panels.value).toHaveLength(2)
    expect(store.panels.value[0]?.model).toEqual(openAiModel)
    expect(store.panels.value[1]?.model).toEqual(googleModel)
  })

  it('streams one shared prompt through every selected model', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'GOOGLE') return [googleModel]
      return []
    })
    apiMock.createChat
      .mockResolvedValueOnce({ chatId: 'chat-1' })
      .mockResolvedValueOnce({ chatId: 'chat-2' })
    apiMock.streamMessage.mockImplementation(
      async (
        _chatId: string,
        input: { modelId: string },
        onEvent: (event: StreamEvent) => void
      ) => {
        onEvent({ type: 'text', content: `${input.modelId} response` })
        onEvent({
          type: 'done',
          promptTokens: 10,
          generationTokens: 5,
          totalTokens: 15
        })
      }
    )

    const store = useCompareStore()
    await store.ensureModels()
    store.sharedPrompt.value = 'same prompt'

    await store.sendComparison()

    expect(apiMock.createChat).toHaveBeenCalledTimes(2)
    expect(apiMock.streamMessage).toHaveBeenCalledTimes(2)
    expect(apiMock.streamMessage.mock.calls[0]?.[1]).toMatchObject({
      content: 'same prompt',
      provider: 'OPENAI',
      modelId: 'gpt-test'
    })
    expect(apiMock.streamMessage.mock.calls[1]?.[1]).toMatchObject({
      content: 'same prompt',
      provider: 'GOOGLE',
      modelId: 'gemini-test'
    })
    expect(store.panels.value[0]?.messages[1]?.content).toBe('gpt-test response')
    expect(store.panels.value[1]?.messages[1]?.content).toBe('gemini-test response')
    expect(store.panels.value[0]?.usage).toEqual({
      promptTokens: 10,
      generationTokens: 5,
      totalTokens: 15
    })
  })

  it('keeps the chosen chat and deletes unkept comparison sessions during cleanup', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'GOOGLE') return [googleModel]
      return []
    })
    apiMock.createChat
      .mockResolvedValueOnce({ chatId: 'chat-1' })
      .mockResolvedValueOnce({ chatId: 'chat-2' })
    apiMock.streamMessage.mockImplementation(
      async (
        _chatId: string,
        input: { modelId: string },
        onEvent: (event: StreamEvent) => void
      ) => {
        onEvent({ type: 'text', content: input.modelId })
        onEvent({ type: 'done' })
      }
    )

    const store = useCompareStore()
    await store.ensureModels()
    store.sharedPrompt.value = 'keep one'
    await store.sendComparison()

    const keptChatId = store.keepPanelChat(store.panels.value[0]!.id)
    await store.cleanupUnkeptChats()

    expect(keptChatId).toBe('chat-1')
    expect(apiMock.deleteChat).toHaveBeenCalledTimes(1)
    expect(apiMock.deleteChat).toHaveBeenCalledWith('chat-2')
    expect(store.panels.value[0]).toMatchObject({
      chatId: 'chat-1',
      kept: true
    })
    expect(store.panels.value[1]).toMatchObject({
      chatId: null,
      messages: []
    })
  })
})
