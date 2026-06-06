import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import type { ModelOption, Provider } from '../useChatApi'
import { chooseDefaultModel, resolveModelOption, useModelStore } from '../useModelStore'

const apiMock = vi.hoisted(() => ({
  fetchModels: vi.fn(),
  requestJson: vi.fn()
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
  vi.stubGlobal('useI18n', () => ({
    t: (key: string, params?: Record<string, unknown>) => {
      if (key === 'models.codexSubscriptionLabel') {
        return `${params?.provider} ChatGPT/Codex: ${params?.model}`
      }
      return key
    }
  }))
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
  const openRouterFreeModel = makeModel(
    'OPENROUTER',
    'openrouter/free',
    'OpenRouter Free'
  )

  beforeEach(() => {
    installNuxtState()
    apiMock.fetchModels.mockReset()
    apiMock.requestJson.mockReset()
    apiMock.requestJson.mockResolvedValue([])
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

  it('defaults to an OpenRouter free model when no saved model is available', async () => {
    apiMock.fetchModels.mockImplementation(async (provider: Provider) => {
      if (provider === 'OPENAI') return [openAiModel]
      if (provider === 'OPENROUTER') return [openRouterFreeModel]
      return []
    })

    const store = useModelStore()
    await store.ensureModels()

    expect(store.defaultModel.value).toEqual(openRouterFreeModel)
  })

  it('can force the OpenRouter free model over a saved model for guest chats', () => {
    expect(
      chooseDefaultModel([openAiModel, openRouterFreeModel], openAiModel, {
        forceOpenRouterFree: true
      })
    ).toEqual(openRouterFreeModel)
  })

  it('ignores stale saved models that are not in the current allowed catalog', () => {
    expect(chooseDefaultModel([openRouterFreeModel], openAiModel)).toEqual(openRouterFreeModel)
    expect(resolveModelOption(openAiModel, [openRouterFreeModel])).toBeNull()
  })

  it('adds ready OpenAI Codex subscription models from the CLI catalog', async () => {
    apiMock.fetchModels.mockResolvedValue([])
    apiMock.requestJson.mockResolvedValueOnce([
      {
        id: 'openai',
        label: 'OpenAI',
        execution: 'native',
        chatProvider: true,
        experimental: false,
        status: 'READY',
        connections: [
          {
            id: 42,
            status: 'READY',
            authMethod: 'SUBSCRIPTION_CLI',
            connectionProfileId: 'codex-subscription',
            defaultModelId: 'gpt-5.5',
            availableModels: [
              { id: 'gpt-5.5', label: 'GPT-5.5', description: 'Latest coding model' },
              { id: 'gpt-5.4-mini', label: 'GPT-5.4 Mini', description: null }
            ]
          }
        ]
      }
    ])

    const store = useModelStore()
    await store.ensureModels()

    expect(store.models.value).toContainEqual(expect.objectContaining({
      provider: 'openai',
      providerId: 'openai',
      value: 'gpt-5.5',
      label: 'OpenAI ChatGPT/Codex: GPT-5.5',
      description: 'Latest coding model',
      connectionId: 42,
      availability: 'READY'
    }))
    expect(store.models.value).toContainEqual(expect.objectContaining({
      value: 'gpt-5.4-mini',
      label: 'OpenAI ChatGPT/Codex: GPT-5.4 Mini',
      connectionId: 42
    }))
  })
})
