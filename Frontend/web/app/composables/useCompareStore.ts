import type { StreamEvent } from '~/composables/chat-stream'
import type { ModelOption, TokenUsage } from '~/composables/useChatApi'
import { isModelLocked, PROVIDERS, useChatApi } from '~/composables/useChatApi'
import { calculateMessageCost } from '~/composables/chat-helpers'
import { useUserSettings } from '~/composables/useUserSettings'

export const COMPARE_MIN_PANELS = 2
export const COMPARE_MAX_PANELS = 6

export type CompareMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export type ComparePanelStatus = 'ready' | 'streaming' | 'error'

export type ComparePanelState = {
  id: string
  chatId: string | null
  model: ModelOption | null
  messages: CompareMessage[]
  status: ComparePanelStatus
  error: string | null
  usage: TokenUsage | null
  kept: boolean
  activeRequest?: AbortController
}

function createId(prefix: string) {
  const uuid = globalThis.crypto?.randomUUID?.()
  if (uuid) return `${prefix}-${uuid}`
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function createMessage(role: CompareMessage['role'], content: string): CompareMessage {
  return {
    id: createId(`compare-${role}`),
    role,
    content,
    createdAt: new Date().toISOString()
  }
}

export function createComparePanel(id: string, model: ModelOption | null = null): ComparePanelState {
  return {
    id,
    chatId: null,
    model,
    messages: [],
    status: 'ready',
    error: null,
    usage: null,
    kept: false
  }
}

function titleFromPrompt(prompt: string) {
  const normalized = prompt.replace(/\s+/g, ' ').trim()
  return normalized.length > 72 ? `${normalized.slice(0, 72).trim()}...` : normalized
}

function errorToString(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}

function isAbortError(error: unknown) {
  if (error instanceof DOMException && error.name === 'AbortError') return true
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && String((error as { name?: unknown }).name) === 'AbortError'
}

export function useCompareStore() {
  const { createChat, deleteChat, fetchModels, streamMessage } = useChatApi()
  const { settings } = useUserSettings()

  const panels = useState<ComparePanelState[]>('compare-panels', () => [
    createComparePanel('compare-panel-1'),
    createComparePanel('compare-panel-2')
  ])
  const panelSequence = useState<number>('compare-panel-sequence', () => 2)
  const sharedPrompt = useState<string>('compare-shared-prompt', () => '')
  const models = useState<ModelOption[]>('compare-models', () => [])
  const modelsLoading = useState<boolean>('compare-models-loading', () => false)
  const modelsError = useState<string | null>('compare-models-error', () => null)

  const sortedModels = computed<ModelOption[]>(() =>
    [...models.value].sort((left, right) => {
      if (left.provider !== right.provider) {
        return PROVIDERS.indexOf(left.provider) - PROVIDERS.indexOf(right.provider)
      }
      return left.label.localeCompare(right.label)
    })
  )

  const availableModels = computed(() => sortedModels.value.filter(model => !isModelLocked(model)))
  const isStreaming = computed(() => panels.value.some(panel => panel.status === 'streaming'))
  const canAddPanel = computed(() => panels.value.length < COMPARE_MAX_PANELS)
  const canRemovePanel = computed(() => panels.value.length > COMPARE_MIN_PANELS && !isStreaming.value)

  const findPanel = (panelId: string) => panels.value.find(panel => panel.id === panelId) || null

  const replacePanel = (panelId: string, patch: Partial<ComparePanelState>) => {
    const index = panels.value.findIndex(panel => panel.id === panelId)
    if (index === -1) return null
    const updated = { ...panels.value[index], ...patch }
    panels.value[index] = updated
    return updated
  }

  const ensureMinimumPanels = () => {
    while (panels.value.length < COMPARE_MIN_PANELS) {
      panelSequence.value += 1
      panels.value.push(createComparePanel(`compare-panel-${panelSequence.value}`))
    }
  }

  const assignDefaultModels = () => {
    const candidates = availableModels.value.length > 0 ? availableModels.value : sortedModels.value
    if (candidates.length === 0) return

    panels.value = panels.value.map((panel, index) => {
      if (panel.model) return panel
      return {
        ...panel,
        model: candidates[index % candidates.length] || null
      }
    })
  }

  const ensureModels = async () => {
    if (modelsLoading.value) return models.value

    modelsLoading.value = true
    try {
      const results = await Promise.allSettled(
        PROVIDERS.map(provider => fetchModels(provider, settings.value.expertMode))
      )
      const loaded: ModelOption[] = []
      const errors: string[] = []

      for (const result of results) {
        if (result.status === 'fulfilled') {
          loaded.push(...result.value)
        } else {
          errors.push(errorToString(result.reason))
        }
      }

      models.value = loaded
      modelsError.value = loaded.length === 0 && errors.length > 0 ? errors[0] : null
      ensureMinimumPanels()
      assignDefaultModels()
    } catch (error) {
      modelsError.value = errorToString(error)
    } finally {
      modelsLoading.value = false
    }

    return models.value
  }

  const deleteUnkeptChat = async (panel: ComparePanelState) => {
    if (!panel.chatId || panel.kept) return
    try {
      await deleteChat(panel.chatId)
    } catch (error) {
      console.error(error)
    }
  }

  const resetPanelResult = async (panelId: string, deleteUnkept = true) => {
    const panel = findPanel(panelId)
    if (!panel) return

    panel.activeRequest?.abort()
    if (deleteUnkept) {
      await deleteUnkeptChat(panel)
    }

    replacePanel(panelId, {
      chatId: panel.kept ? panel.chatId : null,
      messages: [],
      status: 'ready',
      error: null,
      usage: null,
      kept: panel.kept,
      activeRequest: undefined
    })
  }

  const setPanelModel = async (panelId: string, model: ModelOption) => {
    if (isModelLocked(model)) return false
    await resetPanelResult(panelId, true)
    replacePanel(panelId, { model })
    return true
  }

  const addPanel = () => {
    if (!canAddPanel.value) return null

    const usedKeys = new Set(
      panels.value
        .map(panel => panel.model ? `${panel.model.provider}:${panel.model.value}` : null)
        .filter((key): key is string => key !== null)
    )
    const candidates = availableModels.value.length > 0 ? availableModels.value : sortedModels.value
    const model =
      candidates.find(candidate => !usedKeys.has(`${candidate.provider}:${candidate.value}`))
      || candidates[panels.value.length % Math.max(candidates.length, 1)]
      || null

    panelSequence.value += 1
    const panel = createComparePanel(`compare-panel-${panelSequence.value}`, model)
    panels.value = [...panels.value, panel]
    return panel
  }

  const removePanel = async (panelId: string) => {
    if (!canRemovePanel.value) return false
    const panel = findPanel(panelId)
    if (!panel) return false

    panel.activeRequest?.abort()
    await deleteUnkeptChat(panel)
    panels.value = panels.value.filter(item => item.id !== panelId)
    ensureMinimumPanels()
    return true
  }

  const appendAssistantContent = (panelId: string, assistantMessageId: string, content: string) => {
    const panel = findPanel(panelId)
    if (!panel) return

    replacePanel(panelId, {
      messages: panel.messages.map(message =>
        message.id === assistantMessageId
          ? { ...message, content: `${message.content}${content}` }
          : message
      )
    })
  }

  const applyStreamEvent = (
    panelId: string,
    assistantMessageId: string,
    event: StreamEvent
  ) => {
    if (event.type === 'text' && event.content) {
      appendAssistantContent(panelId, assistantMessageId, event.content)
      return
    }

    if (event.type === 'done') {
      replacePanel(panelId, {
        status: 'ready',
        usage: {
          promptTokens: event.promptTokens ?? null,
          generationTokens: event.generationTokens ?? null,
          totalTokens: event.totalTokens ?? null
        }
      })
      return
    }

    if (event.type === 'error') {
      replacePanel(panelId, {
        status: 'error',
        error: event.content?.trim() || null
      })
    }
  }

  const streamPanel = async (panelId: string, prompt: string) => {
    const panel = findPanel(panelId)
    if (!panel?.model) return

    const model = panel.model
    const controller = new AbortController()
    const userMessage = createMessage('user', prompt)
    const assistantMessage = createMessage('assistant', '')

    replacePanel(panelId, {
      chatId: null,
      messages: [userMessage, assistantMessage],
      status: 'streaming',
      error: null,
      usage: null,
      kept: false,
      activeRequest: controller
    })

    try {
      const session = await createChat({ title: titleFromPrompt(prompt) })
      replacePanel(panelId, { chatId: session.chatId })

      if (controller.signal.aborted) {
        return
      }

      await streamMessage(
        session.chatId,
        {
          content: prompt,
          provider: model.provider,
          modelId: model.value
        },
        event => applyStreamEvent(panelId, assistantMessage.id, event),
        controller.signal
      )

      const updated = findPanel(panelId)
      if (updated?.status === 'streaming') {
        replacePanel(panelId, { status: 'ready' })
      }
    } catch (error) {
      if (isAbortError(error)) {
        const updated = findPanel(panelId)
        if (updated?.status === 'streaming') {
          replacePanel(panelId, { status: 'ready' })
        }
      } else {
        replacePanel(panelId, {
          status: 'error',
          error: errorToString(error)
        })
      }
    } finally {
      const updated = findPanel(panelId)
      if (updated?.activeRequest === controller) {
        replacePanel(panelId, { activeRequest: undefined })
      }
    }
  }

  const sendComparison = async () => {
    const prompt = sharedPrompt.value.trim()
    if (!prompt || isStreaming.value) return

    const selectedPanels = panels.value.filter(panel => panel.model && !isModelLocked(panel.model))
    if (selectedPanels.length === 0) return

    await Promise.all(selectedPanels.map(panel => resetPanelResult(panel.id, true)))
    await Promise.allSettled(selectedPanels.map(panel => streamPanel(panel.id, prompt)))
  }

  const cancelAll = () => {
    for (const panel of panels.value) {
      panel.activeRequest?.abort()
      if (panel.status === 'streaming') {
        replacePanel(panel.id, {
          status: 'ready',
          activeRequest: undefined
        })
      }
    }
  }

  const discardPanelChat = async (panelId: string) => {
    const panel = findPanel(panelId)
    if (!panel) return

    panel.activeRequest?.abort()
    await deleteUnkeptChat(panel)
    replacePanel(panelId, {
      chatId: null,
      messages: [],
      status: 'ready',
      error: null,
      usage: null,
      kept: false,
      activeRequest: undefined
    })
  }

  const clearResults = async () => {
    cancelAll()
    await Promise.all(panels.value.map(panel => discardPanelChat(panel.id)))
  }

  const keepPanelChat = (panelId: string) => {
    const panel = findPanel(panelId)
    if (!panel?.chatId) return null

    replacePanel(panelId, { kept: true })
    const activeChatId = useState<string | null>('chat-active-id', () => null)
    activeChatId.value = panel.chatId
    return panel.chatId
  }

  const cleanupUnkeptChats = async () => {
    cancelAll()
    const panelsToDelete = panels.value.filter(panel => panel.chatId && !panel.kept)
    await Promise.allSettled(panelsToDelete.map(panel => deleteUnkeptChat(panel)))
    panels.value = panels.value.map(panel =>
      panel.kept
        ? panel
        : {
            ...panel,
            chatId: null,
            messages: [],
            status: 'ready',
            error: null,
            usage: null,
            activeRequest: undefined
          }
    )
  }

  const getPanelCost = (panel: ComparePanelState) => calculateMessageCost(panel.usage, panel.model)

  ensureMinimumPanels()

  return {
    panels,
    sharedPrompt,
    models,
    modelsLoading,
    modelsError,
    sortedModels,
    isStreaming,
    canAddPanel,
    canRemovePanel,
    ensureModels,
    setPanelModel,
    addPanel,
    removePanel,
    sendComparison,
    cancelAll,
    discardPanelChat,
    clearResults,
    keepPanelChat,
    cleanupUnkeptChats,
    getPanelCost
  }
}
