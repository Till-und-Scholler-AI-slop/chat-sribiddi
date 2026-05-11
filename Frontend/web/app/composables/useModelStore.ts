import type { ModelOption } from '~/composables/useChatApi'
import { PROVIDERS, useChatApi } from '~/composables/useChatApi'
import { favoriteModelKey } from '~/composables/chat-helpers'
import { useUserSettings } from '~/composables/useUserSettings'

const PROVIDER_RANK = new Map(PROVIDERS.map((provider, index) => [provider, index]))

export function useModelStore() {
  const { fetchModels } = useChatApi()
  const { settings } = useUserSettings()

  const models = useState<ModelOption[]>('chat-models', () => [])
  const modelsLoading = useState<boolean>('chat-models-loading', () => false)
  const modelsError = useState<string | null>('chat-models-error', () => null)

  const defaultModel = computed<ModelOption | null>(() => {
    const saved = settings.value.lastUsedModel
    if (saved) {
      const match = models.value.find(m => m.value === saved.value && m.provider === saved.provider)
      if (match) return match
    }
    return models.value[0] || null
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
    modelsLoading.value = true
    try {
      const results = await Promise.allSettled([
        ...PROVIDERS.map(provider => fetchModels(provider))
      ])

      const loaded: ModelOption[] = []
      const errors: string[] = []

      for (const result of results) {
        if (result.status === 'fulfilled') {
          loaded.push(...result.value)
        } else {
          const msg = result.reason instanceof Error ? result.reason.message : String(result.reason)
          errors.push(msg)
          console.error('Failed to load models for a provider:', msg)
        }
      }

      models.value = loaded
      modelsError.value = loaded.length === 0 && errors.length > 0
        ? errors[0]
        : null
    } catch (err) {
      console.error(err)
      modelsError.value =
        err instanceof Error ? err.message : 'Unable to load models from backend.'
    } finally {
      modelsLoading.value = false
    }

    return models.value
  }

  const saveLastUsedModel = (model: ModelOption) => {
    settings.value.lastUsedModel = model
  }

  const resetModels = () => {
    models.value = []
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
    resetModels
  }
}
