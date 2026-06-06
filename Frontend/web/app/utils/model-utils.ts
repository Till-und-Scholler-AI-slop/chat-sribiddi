import type { ModelOption } from '~/composables/useChatApi'

export const PROVIDERS = ['OPENAI', 'GOOGLE', 'CLAUDE', 'OPENROUTER'] as const
export type Provider = (typeof PROVIDERS)[number]

export const PROVIDER_IDS: Record<Provider, string> = {
  OPENAI: 'openai',
  GOOGLE: 'google',
  CLAUDE: 'anthropic',
  OPENROUTER: 'openrouter'
}

export const MODEL_ENDPOINTS: Record<Provider, string> = {
  OPENAI: '/models?provider=openai',
  GOOGLE: '/models?provider=google',
  CLAUDE: '/models?provider=anthropic',
  OPENROUTER: '/models?provider=openrouter'
}

export function isLegacyProvider(value: unknown): value is Provider {
  return typeof value === 'string' && PROVIDERS.includes(value as Provider)
}

export type AvailabilityFlag =
  | 'READY'
  | 'NEEDS_CONNECTION'
  | 'NEEDS_LOCAL_ENDPOINT'
  | 'EXPERIMENTAL'
  | 'DISABLED'
  | 'ALWAYS'
  | 'BYOK_ONLY'

export function isModelLocked(model: Pick<ModelOption, 'availability'>): boolean {
  return model.availability === 'NEEDS_CONNECTION'
    || model.availability === 'NEEDS_LOCAL_ENDPOINT'
    || model.availability === 'EXPERIMENTAL'
    || model.availability === 'DISABLED'
    || model.availability === 'BYOK_ONLY'
}

export function normalizeAvailability(value: unknown): AvailabilityFlag {
  if (typeof value === 'string') {
    const upper = value.toUpperCase()
    if (upper === 'ALWAYS') return 'READY'
    if (upper === 'BYOK_ONLY') return 'NEEDS_CONNECTION'
    if (
      upper === 'READY'
      || upper === 'NEEDS_CONNECTION'
      || upper === 'NEEDS_LOCAL_ENDPOINT'
      || upper === 'EXPERIMENTAL'
      || upper === 'DISABLED'
    ) {
      return upper as AvailabilityFlag
    }
  }
  return 'READY'
}

export function modelEndpointPath(provider: Provider): string {
  return MODEL_ENDPOINTS[provider]
}

export function favoriteModelKey(model: ModelOption): string {
  return `${model.providerId || model.provider}:${model.value}`
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
