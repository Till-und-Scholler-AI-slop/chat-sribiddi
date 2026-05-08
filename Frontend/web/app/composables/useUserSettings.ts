import type { ModelOption } from '~/composables/useChatApi'
import type { BillingMode } from '~/composables/useEntitlements'
import { DEFAULT_BACKEND_BASE_URL } from '~/composables/useChatApi'

type UserSettings = {
  backendBaseUrl: string
  lastUsedModel: ModelOption | null
  favoriteModels: string[]
  showPreSendCostEstimate: boolean
}

const STORAGE_KEY = 'chat-sribiddi:user-settings:v2'

export function useUserSettings() {
  const config = useRuntimeConfig()
  const defaultBackend =
    typeof config.public?.backendBaseUrl === 'string' &&
    config.public.backendBaseUrl.trim() !== ''
      ? config.public.backendBaseUrl.trim()
      : DEFAULT_BACKEND_BASE_URL

  const state = useState<UserSettings>('user-settings', () => ({
    backendBaseUrl: defaultBackend,
    lastUsedModel: null,
    favoriteModels: [],
    showPreSendCostEstimate: true
  }))

  const hydrated = useState<boolean>('user-settings-hydrated', () => false)
  const watcherStarted = useState<boolean>('user-settings-watcher-started', () => false)

  if (import.meta.client && !hydrated.value) {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<UserSettings>
        const savedModel = parsed.lastUsedModel
        state.value = {
          backendBaseUrl: parsed.backendBaseUrl?.trim() || defaultBackend,
          lastUsedModel:
            savedModel && typeof savedModel === 'object' && typeof savedModel.value === 'string' && typeof savedModel.provider === 'string'
              ? savedModel
              : null,
          favoriteModels: Array.isArray(parsed.favoriteModels) ? parsed.favoriteModels : [],
          showPreSendCostEstimate: parsed.showPreSendCostEstimate !== false
        }
      } else {
        state.value.backendBaseUrl ||= defaultBackend
      }
    } catch {
      state.value.backendBaseUrl = defaultBackend
    } finally {
      hydrated.value = true
    }
  }

  if (import.meta.client && !watcherStarted.value) {
    watcherStarted.value = true
    watch(
      state,
      value => {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
      },
      { deep: true }
    )
  }

  const saveBillingMode = async (mode: BillingMode): Promise<{ billingMode: BillingMode }> => {
    const { requestJson } = useChatApi()
    return requestJson<{ billingMode: BillingMode }>('/settings/billing-mode', {
      method: 'PATCH',
      body: JSON.stringify({ billingMode: mode })
    })
  }

  return {
    settings: state,
    hydrated,
    saveBillingMode
  }
}
