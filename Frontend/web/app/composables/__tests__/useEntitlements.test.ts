import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { normalizeEntitlements, useEntitlements, usePlan } from '../useEntitlements'

/**
 * Minimal Nuxt auto-import stubs so the composable can be invoked in a plain
 * Vitest (Node) environment. The real `useState` is SSR-safe and keyed; for
 * tests we back it with a fresh `ref` per key so each test gets an isolated
 * store. A fresh `useNuxtApp()` container is provided so the SSR-safe
 * per-request in-flight holder is exercised.
 */
function installNuxtStubs(
  requestJsonMock: ReturnType<typeof vi.fn>,
  nuxtApp: Record<string, unknown> = {}
) {
  const stateStore = new Map<string, ReturnType<typeof ref>>()

  vi.stubGlobal('useState', (key: string, init: () => unknown) => {
    if (!stateStore.has(key)) {
      stateStore.set(key, ref(init()))
    }
    return stateStore.get(key)!
  })
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('useChatApi', () => ({ requestJson: requestJsonMock }))
  vi.stubGlobal('useNuxtApp', () => nuxtApp)
}

describe('normalizeEntitlements', () => {
  it('returns sensible defaults for null/undefined input', () => {
    expect(normalizeEntitlements(null)).toEqual({
      plan: 'FREE',
      showAds: true,
      allowedModels: null,
      caps: {},
      status: null,
      periodEnd: null,
      cancelAtPeriodEnd: false,
      billingModePreference: 'AUTO'
    })
    expect(normalizeEntitlements(undefined)).toEqual({
      plan: 'FREE',
      showAds: true,
      allowedModels: null,
      caps: {},
      status: null,
      periodEnd: null,
      cancelAtPeriodEnd: false,
      billingModePreference: 'AUTO'
    })
  })

  it('accepts the flat backend shape and folds caps into a nested object', () => {
    const normalized = normalizeEntitlements({
      plan: 'PREMIUM',
      maxAttachments: 4,
      maxAttachmentBytes: 10_000_000,
      maxTotalAttachmentBytes: 50_000_000,
      contextMessageLimit: 40,
      allowedTools: ['web_search'],
      dailyCap: 500,
      parallelStreams: 2,
      maxModelInputUsdPerMillion: 5,
      maxModelOutputUsdPerMillion: 15
    })

    expect(normalized.plan).toBe('PREMIUM')
    expect(normalized.showAds).toBe(false)
    expect(normalized.allowedModels).toBeNull()
    expect(normalized.caps).toEqual({
      maxAttachments: 4,
      maxAttachmentBytes: 10_000_000,
      maxTotalAttachmentBytes: 50_000_000,
      contextMessageLimit: 40,
      allowedTools: ['web_search'],
      dailyCap: 500,
      parallelStreams: 2,
      maxModelInputUsdPerMillion: 5,
      maxModelOutputUsdPerMillion: 15
    })
  })

  it('defaults showAds to true on FREE plan and false otherwise', () => {
    expect(normalizeEntitlements({ plan: 'FREE' }).showAds).toBe(true)
    expect(normalizeEntitlements({ plan: 'PREMIUM' }).showAds).toBe(false)
    expect(normalizeEntitlements({ plan: 'MAX' }).showAds).toBe(false)
  })

  it('lets the backend override showAds explicitly', () => {
    expect(normalizeEntitlements({ plan: 'FREE', showAds: false }).showAds).toBe(false)
    expect(normalizeEntitlements({ plan: 'PREMIUM', showAds: true }).showAds).toBe(true)
  })

  it('falls back to FREE for unknown plan strings', () => {
    expect(normalizeEntitlements({ plan: 'unknown' as unknown as 'FREE' }).plan).toBe('FREE')
  })

  it('uppercases plan values received in lower case', () => {
    expect(normalizeEntitlements({ plan: 'premium' as unknown as 'PREMIUM' }).plan).toBe('PREMIUM')
  })

  it('preserves an explicit allowedModels whitelist', () => {
    const normalized = normalizeEntitlements({
      plan: 'FREE',
      allowedModels: ['openai/gpt-4o-mini', 'google/gemini-2.0-flash']
    })
    expect(normalized.allowedModels).toEqual(['openai/gpt-4o-mini', 'google/gemini-2.0-flash'])
  })

  it('filters non-string entries out of allowedModels', () => {
    const normalized = normalizeEntitlements({
      plan: 'FREE',
      // Backend misbehaving — mixed array must not poison the runtime contract.
      allowedModels: ['openai/gpt-4o-mini', 42, null, { id: 'x' }, 'google/gemini-2.0-flash'] as unknown as string[]
    })
    expect(normalized.allowedModels).toEqual(['openai/gpt-4o-mini', 'google/gemini-2.0-flash'])
  })

  it('treats empty / all-invalid allowedModels as "no whitelist"', () => {
    expect(normalizeEntitlements({ plan: 'FREE', allowedModels: [] }).allowedModels).toBeNull()
    expect(
      normalizeEntitlements({
        plan: 'FREE',
        allowedModels: [1, 2] as unknown as string[]
      }).allowedModels
    ).toBeNull()
  })

  it('surfaces subscription status fields from the backend response', () => {
    const normalized = normalizeEntitlements({
      plan: 'PREMIUM',
      status: 'active',
      periodEnd: '2026-05-01T00:00:00Z',
      cancelAtPeriodEnd: true
    })
    expect(normalized.status).toBe('active')
    expect(normalized.periodEnd).toBe('2026-05-01T00:00:00Z')
    expect(normalized.cancelAtPeriodEnd).toBe(true)
  })

  it('defaults subscription status fields to null/false when missing', () => {
    const normalized = normalizeEntitlements({ plan: 'FREE' })
    expect(normalized.status).toBeNull()
    expect(normalized.periodEnd).toBeNull()
    expect(normalized.cancelAtPeriodEnd).toBe(false)
  })

  it('normalizes the billing mode preference from the backend response', () => {
    expect(normalizeEntitlements({ billingModePreference: 'MANAGED' }).billingModePreference).toBe('MANAGED')
    expect(normalizeEntitlements({ billingModePreference: 'byok' }).billingModePreference).toBe('BYOK')
    expect(normalizeEntitlements({ billingModePreference: 'invalid' }).billingModePreference).toBe('AUTO')
  })
})

describe('useEntitlements', () => {
  let requestJson: ReturnType<typeof vi.fn>

  beforeEach(() => {
    requestJson = vi.fn()
    installNuxtStubs(requestJson)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches GET /billing/me via useChatApi().requestJson() on first load', async () => {
    requestJson.mockResolvedValueOnce({ plan: 'PREMIUM', dailyCap: 1000 })

    const { load, plan, caps, loaded, loading, error } = useEntitlements()

    expect(loaded.value).toBe(false)
    expect(loading.value).toBe(false)

    const result = await load()

    expect(requestJson).toHaveBeenCalledTimes(1)
    expect(requestJson).toHaveBeenCalledWith('/billing/me', { method: 'GET' })
    expect(result?.plan).toBe('PREMIUM')
    expect(plan.value).toBe('PREMIUM')
    expect(caps.value.dailyCap).toBe(1000)
    expect(loaded.value).toBe(true)
    expect(loading.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('loads only once per session (single-flight + cached)', async () => {
    requestJson.mockResolvedValueOnce({ plan: 'FREE' })

    const api = useEntitlements()
    // Fire concurrent + subsequent loads; all should resolve to the same
    // entitlements without triggering additional network calls.
    const [a, b] = await Promise.all([api.load(), api.load()])
    const c = await api.load()
    await api.ensureLoaded()

    expect(requestJson).toHaveBeenCalledTimes(1)
    expect(a?.plan).toBe('FREE')
    expect(b?.plan).toBe('FREE')
    expect(c?.plan).toBe('FREE')
  })

  it('refetches when load({ force: true }) is called', async () => {
    requestJson
      .mockResolvedValueOnce({ plan: 'FREE' })
      .mockResolvedValueOnce({ plan: 'MAX' })

    const { load, plan } = useEntitlements()
    await load()
    expect(plan.value).toBe('FREE')

    await load({ force: true })
    expect(requestJson).toHaveBeenCalledTimes(2)
    expect(plan.value).toBe('MAX')
  })

  it('exposes plan/showAds/allowedModels/caps reactively', async () => {
    requestJson.mockResolvedValueOnce({
      plan: 'FREE',
      allowedModels: ['openai/gpt-4o-mini'],
      maxAttachments: 2,
      contextMessageLimit: 10
    })

    const { load, plan, showAds, allowedModels, caps } = useEntitlements()
    await load()

    expect(plan.value).toBe('FREE')
    expect(showAds.value).toBe(true)
    expect(allowedModels.value).toEqual(['openai/gpt-4o-mini'])
    expect(caps.value).toEqual({ maxAttachments: 2, contextMessageLimit: 10 })
  })

  it('captures errors without throwing and keeps loaded=false', async () => {
    requestJson.mockRejectedValueOnce(new Error('Not authenticated'))

    const { load, error, loaded, plan } = useEntitlements()
    const result = await load()

    expect(result).toBeNull()
    expect(error.value).toBe('Not authenticated')
    expect(loaded.value).toBe(false)
    // Default entitlements remain so consumers can render safely.
    expect(plan.value).toBe('FREE')
  })

  it('retries after an error when load() is called again', async () => {
    requestJson
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ plan: 'MAX' })

    const { load, plan, loaded, error } = useEntitlements()
    await load()
    expect(error.value).toBe('boom')
    expect(loaded.value).toBe(false)

    await load()
    expect(requestJson).toHaveBeenCalledTimes(2)
    expect(plan.value).toBe('MAX')
    expect(loaded.value).toBe(true)
    expect(error.value).toBeNull()
  })

  it('shares state across useEntitlements() calls via useState("billing-entitlements")', async () => {
    requestJson.mockResolvedValueOnce({ plan: 'PREMIUM' })

    const a = useEntitlements()
    await a.load()

    const b = useEntitlements()
    expect(b.plan.value).toBe('PREMIUM')
    expect(b.loaded.value).toBe(true)
    // Loading again via a different handle must hit the cache, not the network.
    await b.ensureLoaded()
    expect(requestJson).toHaveBeenCalledTimes(1)
  })

  it('shares the in-flight request across concurrent useEntitlements() handles', async () => {
    // Delay the resolution so both loads are truly concurrent and rely on the
    // per-request in-flight promise (not the `loaded` cache).
    let resolveFetch!: (value: unknown) => void
    requestJson.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveFetch = resolve
      })
    )

    const a = useEntitlements()
    const b = useEntitlements()

    const pa = a.load()
    const pb = b.load()

    resolveFetch({ plan: 'PREMIUM' })
    await Promise.all([pa, pb])

    expect(requestJson).toHaveBeenCalledTimes(1)
    expect(a.plan.value).toBe('PREMIUM')
    expect(b.plan.value).toBe('PREMIUM')
  })

  it('reset() clears cached state so the next load() refetches', async () => {
    requestJson
      .mockResolvedValueOnce({ plan: 'PREMIUM' })
      .mockResolvedValueOnce({ plan: 'FREE' })

    const { load, reset, plan, loaded } = useEntitlements()
    await load()
    expect(plan.value).toBe('PREMIUM')

    reset()
    expect(loaded.value).toBe(false)

    await load()
    expect(requestJson).toHaveBeenCalledTimes(2)
    expect(plan.value).toBe('FREE')
  })

  it('returns a non-mutable default when no data has been loaded', () => {
    const { entitlements, caps } = useEntitlements()

    // The default snapshot must be safe to read but not leak mutations across
    // callers. Writing to the frozen fallback throws in strict mode (ES modules
    // are always strict); writing to `caps.value` — which is a fresh object —
    // must not persist into the next reader's view.
    expect(() => {
      ;(entitlements.value as { plan: string }).plan = 'MAX'
    }).toThrow()

    ;(caps.value as Record<string, unknown>).injected = true
    const { caps: caps2 } = useEntitlements()
    expect(caps2.value).toEqual({})
  })

  it('scopes the in-flight request per useNuxtApp() context (SSR isolation)', async () => {
    // Simulate SSR request A — load starts but hasn't resolved yet.
    let resolveA!: (value: unknown) => void
    const requestJsonA = vi.fn().mockReturnValueOnce(
      new Promise((resolve) => {
        resolveA = resolve
      })
    )
    installNuxtStubs(requestJsonA, { tag: 'request-A' })
    const a = useEntitlements()
    const pa = a.load()

    // Simulate SSR request B running in parallel with its own Nuxt context.
    // Its load() must not receive request A's promise (which would leak A's
    // entitlements to B's user).
    const requestJsonB = vi.fn().mockResolvedValueOnce({ plan: 'MAX' })
    installNuxtStubs(requestJsonB, { tag: 'request-B' })
    const b = useEntitlements()
    await b.load()
    expect(requestJsonB).toHaveBeenCalledTimes(1)
    expect(b.plan.value).toBe('MAX')

    // Now resolve request A — it must still fetch independently.
    installNuxtStubs(requestJsonA, { tag: 'request-A' })
    resolveA({ plan: 'FREE' })
    await pa
    expect(requestJsonA).toHaveBeenCalledTimes(1)
  })
})

describe('usePlan', () => {
  let requestJson: ReturnType<typeof vi.fn>

  beforeEach(() => {
    requestJson = vi.fn()
    installNuxtStubs(requestJson)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('exposes the reactive plan and shares state with useEntitlements', async () => {
    requestJson.mockResolvedValueOnce({ plan: 'MAX' })

    const { plan, ensureLoaded } = usePlan()
    expect(plan.value).toBe('FREE') // default before load

    await ensureLoaded()
    expect(plan.value).toBe('MAX')

    const ent = useEntitlements()
    expect(ent.plan.value).toBe('MAX')
  })
})
