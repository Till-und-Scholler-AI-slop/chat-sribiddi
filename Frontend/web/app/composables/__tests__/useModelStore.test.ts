import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import type { ModelOption, Provider } from '../useChatApi'
import { useModelStore } from '../useModelStore'

const apiMock = vi.hoisted(() => ({
  fetchModels: vi.fn()
}))

vi.mock('~/composables/useChatApi', () => ({
  DEFAULT_BACKEND_BASE_URL: 'http://localhost:8080',
  PROVIDERS: ['OPENAI', 'GOOGLE', 'CLAUDE', 'OPENROUTER'],
  useChatApi: () => apiMock
}))

const makeModel = (provider: Provider, value: string, label: string): ModelOption => ({
  provider,
  value,
  label
})

function installNuxtState() {
  const state = new Map<string, ReturnType<typeof ref>>()
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('useRuntimeConfig', () => ({ public: { backendBaseUrl: '' } }))
  vi.stubGlobal('useState', (key: string, init: () => unknown) => {
    if (!state.has(key)) {
      state.set(key, ref(init()))
    }
    return state.get(key)
  })
}

describe('useModelStore', () => {
  const openAiModel = makeModel('OPENAI', 'gpt-test', 'GPT Test')
  const googleModel = makeModel('GOOGLE', 'gemini-test', 'Gemini Test')

  beforeEach(() => {
    installNuxtState()
    apiMock.fetchModels.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads models from all providers and sorts favorites first', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'GOOGLE') return [googleModel]
      return []
    })

    const store = useModelStore()
    await store.ensureModels()

    expect(store.models.value).toEqual([openAiModel, googleModel])
    expect(store.sortedModels.value).toEqual([openAiModel, googleModel])

    store.toggleFavorite(googleModel)

    expect(store.isFavorite(googleModel)).toBe(true)
    expect(store.sortedModels.value).toEqual([googleModel, openAiModel])
  })

  it('persists and resolves the last-used model', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'GOOGLE') return [googleModel]
      return []
    })

    const store = useModelStore()
    await store.ensureModels()

    expect(store.defaultModel.value).toEqual(openAiModel)

    store.saveLastUsedModel(googleModel)

    expect(store.defaultModel.value).toEqual(googleModel)
  })
})
