import type { ModelOption, Provider } from '~/composables/useChatApi'

export const PROVIDERS = ['OPENAI', 'GOOGLE', 'CLAUDE', 'OPENROUTER'] as const

export const MODEL_ENDPOINTS: Record<Provider, string> = {
  OPENAI: '/models/openai',
  GOOGLE: '/models/google',
  CLAUDE: '/models/claude',
  OPENROUTER: '/models/openrouter'
}

export type AvailabilityFlag = 'ALWAYS' | 'BYOK_ONLY'

export function isModelLocked(model: Pick<ModelOption, 'availability'>): boolean {
  return model.availability === 'BYOK_ONLY'
}

export function normalizeAvailability(value: unknown): AvailabilityFlag {
  if (typeof value === 'string') {
    const upper = value.toUpperCase()
    if (upper === 'ALWAYS' || upper === 'BYOK_ONLY') {
      return upper
    }
  }
  return 'ALWAYS'
}

export function modelEndpointPath(provider: Provider): string {
  return MODEL_ENDPOINTS[provider]
}

export function favoriteModelKey(model: ModelOption): string {
  return `${model.provider}:${model.value}`
}

export function isAbortError(error: unknown): boolean {
  if (error instanceof DOMException && error.name === 'AbortError') return true
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && String((error as { name?: unknown }).name) === 'AbortError'
}

export function rekeyStateEntry<T>(map: Record<string, T>, oldId: string, newId: string): void {
  if (oldId === newId || !(oldId in map)) return
  map[newId] = map[oldId]
  Reflect.deleteProperty(map, oldId)
}
