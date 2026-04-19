import { computed } from 'vue'

/**
 * User plan tier as returned by the backend. Matches the Java enum
 * {@code com.rft.chat_sribiddi.auth.Plan}.
 */
export type Plan = 'FREE' | 'PREMIUM' | 'MAX'

/**
 * Per-plan limits ("caps"). Fields are optional so the shape can grow with the
 * backend without breaking consumers. Values mirror the Java record
 * {@code com.rft.chat_sribiddi.billing.Entitlements} (aside from the
 * {@code plan} field which is surfaced separately).
 */
export interface EntitlementCaps {
  maxAttachments?: number
  maxAttachmentBytes?: number
  maxTotalAttachmentBytes?: number
  contextMessageLimit?: number
  allowedTools?: string[]
  dailyCap?: number
  parallelStreams?: number
  maxModelInputUsdPerMillion?: number | null
  maxModelOutputUsdPerMillion?: number | null
}

/**
 * Effective entitlements for the current user as returned by
 * {@code GET /billing/me}. The response is intentionally loose — unknown
 * fields are preserved in {@link EntitlementCaps} and new fields can be added
 * without breaking the composable.
 */
export interface Entitlements {
  plan: Plan
  showAds: boolean
  /**
   * Explicit list of allowed model IDs. {@code null} means "no whitelist" —
   * any model the backend knows about is allowed (price-based gating may still
   * apply via {@link EntitlementCaps.maxModelInputUsdPerMillion}).
   */
  allowedModels: string[] | null
  caps: EntitlementCaps
}

/** Default entitlements used before {@code /billing/me} has loaded. Frozen so
 * consumers that receive this fallback through {@code entitlements.value} cannot
 * mutate the shared default (which would leak across all callers). */
const DEFAULT_ENTITLEMENTS: Entitlements = Object.freeze({
  plan: 'FREE',
  showAds: true,
  allowedModels: null,
  caps: Object.freeze({}) as EntitlementCaps
}) as Entitlements

type RawEntitlementsResponse = Partial<Entitlements & EntitlementCaps> & {
  caps?: Partial<EntitlementCaps>
  allowedModels?: unknown
}

function normalizePlan(value: unknown): Plan {
  if (typeof value === 'string') {
    const upper = value.toUpperCase()
    if (upper === 'FREE' || upper === 'PREMIUM' || upper === 'MAX') {
      return upper
    }
  }
  return 'FREE'
}

/**
 * Normalises the raw {@code /billing/me} response into a stable {@link Entitlements}
 * shape. The backend currently returns caps as flat fields on the top-level
 * object (mirroring the Java record). We accept both flat and nested
 * ({@code caps: {...}}) shapes so the frontend stays forward-compatible.
 *
 * Precedence: when a cap appears in both places, the nested {@code caps}
 * object wins over the flat top-level field.
 */
export function normalizeEntitlements(raw: RawEntitlementsResponse | null | undefined): Entitlements {
  if (!raw) return { ...DEFAULT_ENTITLEMENTS, caps: {} }

  const plan = normalizePlan(raw.plan)

  const flatCaps: EntitlementCaps = {
    maxAttachments: raw.maxAttachments,
    maxAttachmentBytes: raw.maxAttachmentBytes,
    maxTotalAttachmentBytes: raw.maxTotalAttachmentBytes,
    contextMessageLimit: raw.contextMessageLimit,
    allowedTools: raw.allowedTools,
    dailyCap: raw.dailyCap,
    parallelStreams: raw.parallelStreams,
    maxModelInputUsdPerMillion: raw.maxModelInputUsdPerMillion,
    maxModelOutputUsdPerMillion: raw.maxModelOutputUsdPerMillion
  }

  const caps: EntitlementCaps = { ...flatCaps, ...(raw.caps ?? {}) }

  // Prune undefined keys so consumers can do `'x' in caps` cleanly, and so
  // that fields the backend didn't send don't leak into the normalized shape.
  for (const key of Object.keys(caps) as (keyof EntitlementCaps)[]) {
    if (caps[key] === undefined) delete caps[key]
  }

  // `showAds` defaults to true on the Free plan and false otherwise, unless the
  // backend explicitly overrides it.
  const showAds = typeof raw.showAds === 'boolean' ? raw.showAds : plan === 'FREE'

  // Validate allowed-models entries are strings and treat empty lists as "no
  // whitelist" (same semantics as a missing field) so the runtime contract
  // (`string[] | null`) is preserved even if the backend returns mixed types.
  const filteredModels = Array.isArray(raw.allowedModels)
    ? raw.allowedModels.filter((model): model is string => typeof model === 'string')
    : null
  const allowedModels = filteredModels && filteredModels.length > 0 ? filteredModels : null

  return { plan, showAds, allowedModels, caps }
}

type EntitlementsState = {
  data: Entitlements | null
  loading: boolean
  error: string | null
  loaded: boolean
}

/** Holder object so we can mutate a shared `current` field without losing the
 * reference. Stored on the per-request `useNuxtApp()` context on SSR so the
 * in-flight promise is never shared between concurrent requests (preventing
 * one user's entitlements from being returned to another). Falls back to a
 * module-level holder on the client (single renderer) and in unit tests. */
type InFlightHolder = { current: Promise<Entitlements | null> | null }

const INFLIGHT_KEY = '__billingEntitlementsInFlight' as const
const moduleInFlight: InFlightHolder = { current: null }

function getInFlightHolder(): InFlightHolder {
  // `useNuxtApp` is only available inside a Nuxt runtime. Under SSR it is a
  // per-request instance, so attaching the promise to it isolates concurrent
  // requests. Guarded so the composable still works in plain Vitest.
  const getNuxtApp = (globalThis as unknown as { useNuxtApp?: () => Record<string, unknown> }).useNuxtApp
  if (typeof getNuxtApp !== 'function') return moduleInFlight
  try {
    const app = getNuxtApp() as Record<string, InFlightHolder | undefined>
    if (!app[INFLIGHT_KEY]) {
      app[INFLIGHT_KEY] = { current: null }
    }
    return app[INFLIGHT_KEY]!
  } catch {
    return moduleInFlight
  }
}

/**
 * Loads {@code GET /billing/me} once per session and exposes the resulting
 * {@link Entitlements} as SSR-safe reactive state via
 * {@code useState('billing-entitlements', …)} (namespaced to match repo
 * conventions like {@code chat-*} / {@code auth-*}).
 *
 * Uses {@code useChatApi().requestJson()} per repo convention — the CSRF header
 * is attached automatically for mutating methods and the backend base URL is
 * resolved centrally.
 *
 * The fetch is single-flight: concurrent callers share the same promise, and
 * once the response has been received it is reused for the rest of the session
 * (call {@link reset} or {@link load} with {@code force=true} to refetch). On
 * SSR the in-flight promise is scoped to {@code useNuxtApp()} to avoid
 * cross-request data leaks.
 */
export function useEntitlements() {
  const state = useState<EntitlementsState>('billing-entitlements', () => ({
    data: null,
    loading: false,
    error: null,
    loaded: false
  }))

  const load = async (options: { force?: boolean } = {}): Promise<Entitlements | null> => {
    const { force = false } = options
    if (!force && state.value.loaded) return state.value.data

    const holder = getInFlightHolder()
    if (holder.current) return holder.current

    state.value = { ...state.value, loading: true, error: null }

    const { requestJson } = useChatApi()
    holder.current = (async () => {
      try {
        const raw = await requestJson<RawEntitlementsResponse | null>('/billing/me', { method: 'GET' })
        const normalized = normalizeEntitlements(raw)
        state.value = { data: normalized, loading: false, error: null, loaded: true }
        return normalized
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err)
        state.value = { data: state.value.data, loading: false, error: message, loaded: false }
        return null
      } finally {
        holder.current = null
      }
    })()

    return holder.current
  }

  const ensureLoaded = async (): Promise<Entitlements | null> => {
    if (state.value.loaded) return state.value.data
    return load()
  }

  const reset = () => {
    state.value = { data: null, loading: false, error: null, loaded: false }
    getInFlightHolder().current = null
  }

  const entitlements = computed<Entitlements>(() => state.value.data ?? DEFAULT_ENTITLEMENTS)
  const plan = computed<Plan>(() => entitlements.value.plan)
  const showAds = computed<boolean>(() => entitlements.value.showAds)
  const allowedModels = computed<string[] | null>(() => entitlements.value.allowedModels)
  const caps = computed<EntitlementCaps>(() => state.value.data?.caps ?? {})
  const loading = computed<boolean>(() => state.value.loading)
  const error = computed<string | null>(() => state.value.error)
  const loaded = computed<boolean>(() => state.value.loaded)

  return {
    entitlements,
    plan,
    showAds,
    allowedModels,
    caps,
    loading,
    error,
    loaded,
    load,
    ensureLoaded,
    reset
  }
}

/**
 * Convenience wrapper exposing only the reactive {@link Plan}. Useful for
 * components that only need to branch on the tier (e.g. "Upgrade" CTAs) and
 * don't care about the full entitlements payload.
 */
export function usePlan() {
  const { plan, loaded, loading, error, ensureLoaded } = useEntitlements()
  return { plan, loaded, loading, error, ensureLoaded }
}
