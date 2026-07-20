import type { ModelOption } from '~/composables/useChatApi'
import { useChatApi } from '~/composables/useChatApi'
import { favoriteModelKey, PROVIDERS } from '~/utils/model-utils'
import { fetchAllProviderModels } from '~/utils/model-fetch'
import { useUserSettings } from '~/composables/useUserSettings'
import { useKeyApi } from '~/composables/useKeyApi'

const PROVIDER_RANK = new Map(PROVIDERS.map((provider, index) => [provider, index]))

type ModelLoadCoordinator = {
  generation: number
  key: string | null
  promise: Promise<ModelOption[]> | null
}

const modelLoadCoordinators = new WeakMap<object, ModelLoadCoordinator>()

function modelCoordinatorFor(key: object): ModelLoadCoordinator {
  let coordinator = modelLoadCoordinators.get(key)
  if (!coordinator) {
    coordinator = { generation: 0, key: null, promise: null }
    modelLoadCoordinators.set(key, coordinator)
  }
  return coordinator
}

const sameModel = (left: ModelOption, right: Pick<ModelOption, 'provider' | 'value'>) =>
  left.provider === right.provider && left.value === right.value

export const isSelectableModel = (model: Pick<ModelOption, 'availability'>): boolean =>
  !model.availability || model.availability === 'READY' || model.availability === 'ALWAYS'

export const isOpenRouterFreeModel = (model: Pick<ModelOption, 'provider' | 'value'>): boolean =>
  model.provider === 'OPENROUTER' && model.value === 'openrouter/free'

export function resolveModelOption(
  model: Pick<ModelOption, 'provider' | 'value'> | null | undefined,
  models: ModelOption[]
): ModelOption | null {
  if (!model) return null
  return models.find(option => sameModel(option, model) && isSelectableModel(option)) || null
}

export function chooseDefaultModel(
  models: ModelOption[],
  saved: Pick<ModelOption, 'provider' | 'value'> | null | undefined,
  options: { forceOpenRouterFree?: boolean } = {}
): ModelOption | null {
  const selectable = models.filter(isSelectableModel)
  const openRouterFree = selectable.find(isOpenRouterFreeModel) || null
  if (options.forceOpenRouterFree) return openRouterFree
  return (
    resolveModelOption(saved, selectable) ||
    openRouterFree ||
    selectable[0] ||
    null
  )
}

export type GatewayProviderModelSource = {
  id: string
  label: string
  execution: string
  chatProvider: boolean
  experimental: boolean
  connections: Array<{
    id: number
    status: string
    authMethod?: string
    connectionProfileId?: string | null
    defaultModelId?: string | null
    availableModels?: Array<{
      id: string
      label: string
      description?: string | null
    }>
  }>
}

type ModelLabelTranslator = (key: string, params?: Record<string, unknown>) => string

export function gatewayModelsFromProviders(
  providers: GatewayProviderModelSource[],
  t?: ModelLabelTranslator
): ModelOption[] {
  return providers.flatMap(provider => {
    if (!provider.chatProvider) {
      return []
    }
    if (provider.id === 'openai' && t) return loadCodexSubscriptionModels(provider, t)
    if (provider.execution !== 'gateway' || provider.experimental) return []
    return provider.connections
      .filter(connection => connection.status === 'READY' && !!connection.defaultModelId?.trim())
      .map(connection => {
        const modelId = connection.defaultModelId!.trim()
        return {
          label: t
            ? t('models.gatewayConnectionLabel', { provider: provider.label, model: modelId })
            : `${provider.label}: ${modelId}`,
          value: modelId,
          provider: provider.id,
          providerId: provider.id,
          availability: 'READY' as const,
          canonicalSlug: `${provider.id}/${modelId}`,
          description: null,
          connectionId: connection.id
        } satisfies ModelOption
      })
  })
}

export function useModelStore() {
  const { fetchModels } = useChatApi()
  const { getProviders } = useKeyApi()
  const { settings } = useUserSettings()
  const { t } = useI18n()
  const coordinatorKey = typeof useNuxtApp === 'function' ? useNuxtApp() : settings
  const loadCoordinator = modelCoordinatorFor(coordinatorKey)

  const models = useState<ModelOption[]>('chat-models', () => [])
  const modelsLoading = useState<boolean>('chat-models-loading', () => false)
  const modelsError = useState<string | null>('chat-models-error', () => null)
  const loadedExpertMode = useState<boolean | null>('chat-models-expert-mode', () => null)

  const defaultModel = computed<ModelOption | null>(() => {
    return chooseDefaultModel(models.value, settings.value.lastUsedModel)
  })

  const favoriteModelKeys = computed(() => new Set(settings.value.favoriteModels))

  const isFavorite = (model: ModelOption): boolean =>
    favoriteModelKeys.value.has(favoriteModelKey(model))

  const toggleFavorite = (model: ModelOption) => {
    const key = favoriteModelKey(model)
    const favorites = settings.value.favoriteModels
    if (favorites.includes(key)) {
      settings.value.favoriteModels = favorites.filter(k => k !== key)
    } else {
      settings.value.favoriteModels = [...favorites, key]
    }
  }

  const sortedModels = computed<ModelOption[]>(() =>
    [...models.value].sort((a, b) => {
      const aFav = isFavorite(a) ? 0 : 1
      const bFav = isFavorite(b) ? 0 : 1
      if (aFav !== bFav) return aFav - bFav
      if (a.provider !== b.provider) {
        return (PROVIDER_RANK.get(a.provider) ?? 0) - (PROVIDER_RANK.get(b.provider) ?? 0)
      }
      return a.label.localeCompare(b.label)
    })
  )

  const ensureModels = async () => {
    if (models.value.length > 0 && loadedExpertMode.value === settings.value.expertMode) {
      return models.value
    }

    const requestedExpertMode = settings.value.expertMode
    const requestKey = `${settings.value.backendBaseUrl.trim()}|${requestedExpertMode}`
    if (loadCoordinator.promise && loadCoordinator.key === requestKey) {
      return loadCoordinator.promise
    }

    const generation = ++loadCoordinator.generation
    loadCoordinator.key = requestKey
    modelsLoading.value = true
    const promise = (async () => {
      try {
        const [{ models: loaded, errors }, gatewayModels] = await Promise.all([
          fetchAllProviderModels(fetchModels, requestedExpertMode),
          loadGatewayModels(getProviders, t)
        ])
        if (
          generation === loadCoordinator.generation
          && loadCoordinator.key === requestKey
          && settings.value.expertMode === requestedExpertMode
        ) {
          models.value = [...loaded, ...gatewayModels]
          loadedExpertMode.value = requestedExpertMode
          modelsError.value = loaded.length === 0 && errors.length > 0
            ? errors[0]
            : null
        }
      } catch (err) {
        if (generation === loadCoordinator.generation && loadCoordinator.key === requestKey) {
          console.error(err)
          modelsError.value =
            err instanceof Error ? err.message : 'Unable to load models from backend.'
        }
      } finally {
        if (generation === loadCoordinator.generation && loadCoordinator.key === requestKey) {
          modelsLoading.value = false
        }
      }
      return models.value
    })()

    loadCoordinator.promise = promise
    try {
      return await promise
    } finally {
      if (loadCoordinator.promise === promise) loadCoordinator.promise = null
    }
  }

  const saveLastUsedModel = (model: ModelOption) => {
    settings.value.lastUsedModel = model
  }

  const resolveModel = (model: ModelOption | null | undefined) =>
    resolveModelOption(model, models.value)

  const resetModels = () => {
    loadCoordinator.generation++
    loadCoordinator.key = null
    loadCoordinator.promise = null
    models.value = []
    loadedExpertMode.value = null
    modelsError.value = null
    modelsLoading.value = false
  }

  return {
    models,
    sortedModels,
    modelsLoading,
    modelsError,
    defaultModel,
    isFavorite,
    toggleFavorite,
    ensureModels,
    saveLastUsedModel,
    resolveModel,
    resetModels
  }
}

async function loadGatewayModels(
  getProviders: () => Promise<GatewayProviderModelSource[]>,
  t: ModelLabelTranslator
): Promise<ModelOption[]> {
  try {
    const providers = await getProviders()
    return gatewayModelsFromProviders(providers, t)
  } catch (err) {
    console.warn('Unable to load gateway provider models', err)
    return []
  }
}

function loadCodexSubscriptionModels(
  provider: {
    id: string
    label: string
    connections: Array<{
      id: number
      status: string
      authMethod?: string
      connectionProfileId?: string | null
      defaultModelId?: string | null
      availableModels?: Array<{
        id: string
        label: string
        description?: string | null
      }>
    }>
  },
  t: (key: string, params?: Record<string, unknown>) => string
): ModelOption[] {
  return provider.connections
    .filter(connection =>
      connection.status === 'READY' &&
      connection.authMethod === 'SUBSCRIPTION_CLI' &&
      connection.connectionProfileId === 'codex-subscription' &&
      codexConnectionModels(connection).length > 0
    )
    .flatMap(connection => {
      return codexConnectionModels(connection).map(model => ({
        label: t('models.codexSubscriptionLabel', { provider: provider.label, model: model.label }),
        value: model.id,
        provider: provider.id,
        providerId: provider.id,
        availability: 'READY' as const,
        canonicalSlug: `${provider.id}/codex-subscription/${model.id}`,
        description: model.description ?? null,
        connectionId: connection.id
      }) satisfies ModelOption)
    })
}

function codexConnectionModels(connection: {
  defaultModelId?: string | null
  availableModels?: Array<{
    id: string
    label: string
    description?: string | null
  }>
}) {
  const models = (connection.availableModels ?? [])
    .map(model => ({
      id: model.id?.trim(),
      label: model.label?.trim() || model.id?.trim(),
      description: model.description ?? null
    }))
    .filter((model): model is { id: string, label: string, description: string | null } =>
      Boolean(model.id && model.label)
    )
  if (models.length > 0) return models
  const fallback = connection.defaultModelId?.trim()
  return fallback ? [{ id: fallback, label: fallback, description: null }] : []
}
