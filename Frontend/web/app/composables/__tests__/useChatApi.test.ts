import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { computed, ref } from 'vue'
import {
  isModelLocked,
  isTerminalStreamEvent,
  modelEndpointPath,
  normalizeAvailability,
  normalizeBaseUrl,
  normalizeRequiredPlan,
  parseSseEvents,
  REQUEST_USER_ID_HEADER,
  useChatApi,
  withRequestUserId
} from '../useChatApi'

describe('normalizeBaseUrl', () => {
  it('removes trailing slash', () => {
    expect(normalizeBaseUrl('http://localhost:8080/')).toBe('http://localhost:8080')
  })

  it('leaves URL without trailing slash unchanged', () => {
    expect(normalizeBaseUrl('http://localhost:8080')).toBe('http://localhost:8080')
  })

  it('removes only the trailing slash, not path slashes', () => {
    expect(normalizeBaseUrl('http://example.com/api/')).toBe('http://example.com/api')
  })

  it('handles empty string', () => {
    expect(normalizeBaseUrl('')).toBe('')
  })
})

describe('modelEndpointPath', () => {
  it('returns the OpenRouter catalog endpoint', () => {
    expect(modelEndpointPath('OPENROUTER')).toBe('/models/openrouter')
  })
})

describe('withRequestUserId', () => {
  it('adds the request user header when a user id is present', () => {
    expect(withRequestUserId({}, ' user-123 ')).toEqual({
      [REQUEST_USER_ID_HEADER]: 'user-123'
    })
  })

  it('keeps existing user id headers untouched', () => {
    expect(withRequestUserId({ [REQUEST_USER_ID_HEADER]: 'existing-user' }, 'user-123')).toEqual({
      [REQUEST_USER_ID_HEADER]: 'existing-user'
    })
  })

  it('does not add the header for empty user ids', () => {
    expect(withRequestUserId({}, null)).toEqual({})
    expect(withRequestUserId({}, '')).toEqual({})
    expect(withRequestUserId({}, '   ')).toEqual({})
  })
})

describe('parseSseEvents', () => {
  it('parses a text event', () => {
    const raw = 'event: text\ndata: {"type":"text","content":"Hello"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'text', content: 'Hello' }])
  })

  it('parses a reasoning event', () => {
    const raw = 'event: reasoning\ndata: {"type":"reasoning","content":"Let me think..."}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'reasoning', content: 'Let me think...' }])
  })

  it('parses a done event with token usage', () => {
    const raw = 'event: done\ndata: {"type":"done","content":null,"promptTokens":50,"generationTokens":20,"totalTokens":70,"messageId":123,"hasToolCalls":true}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{
      type: 'done',
      content: null,
      promptTokens: 50,
      generationTokens: 20,
      totalTokens: 70,
      messageId: 123,
      hasToolCalls: true
    }])
  })

  it('parses an error event', () => {
    const raw = 'event: error\ndata: {"type":"error","content":"Something went wrong"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'error', content: 'Something went wrong' }])
  })

  it('parses stream error metadata fields', () => {
    const raw = 'event: error\ndata: {"type":"error","provider":"GOOGLE","modelId":"gemma-4-31b-it","statusCode":"503","errorType":"server_error","receivedBytes":"128","incomplete":true}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{
      type: 'error',
      provider: 'GOOGLE',
      modelId: 'gemma-4-31b-it',
      statusCode: 503,
      errorType: 'SERVER_ERROR',
      receivedBytes: 128,
      incomplete: true
    }])
  })

  it('parses string boolean metadata for incomplete strictly', () => {
    const raw = 'event: error\ndata: {"type":"error","incomplete":"false"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'error', incomplete: false }])
  })

  it('parses multiple events in one chunk', () => {
    const raw = 'event: text\ndata: {"type":"text","content":"Hello"}\n\nevent: text\ndata: {"type":"text","content":" world"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toHaveLength(2)
    expect(events[0]).toEqual({ type: 'text', content: 'Hello' })
    expect(events[1]).toEqual({ type: 'text', content: ' world' })
  })

  it('parses CRLF-delimited events progressively', () => {
    const raw = 'event: text\r\ndata: {"type":"text","content":"Hello"}\r\n\r\nevent: text\r\ndata: {"type":"text","content":" world"}\r\n\r\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([
      { type: 'text', content: 'Hello' },
      { type: 'text', content: ' world' }
    ])
  })

  it('parses CR-delimited events when carriage returns are split from line feeds', () => {
    const raw = 'event: text\rdata: {"type":"text","content":"Hello"}\r\revent: text\rdata: {"type":"text","content":" world"}\r\r'
    const events = parseSseEvents(raw)
    expect(events).toEqual([
      { type: 'text', content: 'Hello' },
      { type: 'text', content: ' world' }
    ])
  })

  it('parses data-only events without event field', () => {
    const raw = 'data: {"type":"text","content":"Hi"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'text', content: 'Hi' }])
  })

  it('joins multi-line SSE data fields', () => {
    const raw = 'data: {"type":"text",\ndata: "content":"Hello"}\n\n'
    const events = parseSseEvents(raw)
    expect(events).toEqual([{ type: 'text', content: 'Hello' }])
  })

  it('ignores empty input', () => {
    expect(parseSseEvents('')).toEqual([])
    expect(parseSseEvents('\n\n')).toEqual([])
  })

  it('ignores malformed JSON', () => {
    const raw = 'data: not-json\n\n'
    expect(parseSseEvents(raw)).toEqual([])
  })

  it('ignores events without data field', () => {
    const raw = 'event: text\n\n'
    expect(parseSseEvents(raw)).toEqual([])
  })
})

describe('isTerminalStreamEvent', () => {
  it('treats done events as terminal', () => {
    expect(isTerminalStreamEvent({ type: 'done' })).toBe(true)
  })

  it('treats error events as terminal', () => {
    expect(isTerminalStreamEvent({ type: 'error', content: 'Rate limited' })).toBe(true)
  })

  it('keeps text and reasoning events non-terminal', () => {
    expect(isTerminalStreamEvent({ type: 'text', content: 'Hello' })).toBe(false)
    expect(isTerminalStreamEvent({ type: 'reasoning', content: 'Thinking' })).toBe(false)
  })
})

describe('useChatApi.logout', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    // Minimal Nuxt/Vue auto-import stubs so useChatApi() can be invoked in a
    // plain Vitest (Node) environment.
    vi.stubGlobal('useRuntimeConfig', () => ({
      public: { backendBaseUrl: 'http://localhost:8080' }
    }))
    vi.stubGlobal('useUserSettings', () => ({
      settings: ref({ backendBaseUrl: '' })
    }))
    vi.stubGlobal('useState', (_key: string, init: () => unknown) => ref(init()))
    vi.stubGlobal('computed', computed)

    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    // Stub a minimal `document` so getCsrfToken() can read XSRF-TOKEN.
    vi.stubGlobal('document', { cookie: '' } as unknown as Document)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns true on a 204 response and POSTs to /logout with the CSRF header', async () => {
    ;(globalThis as unknown as { document: { cookie: string } }).document.cookie =
      'XSRF-TOKEN=csrf-token-value'
    fetchMock.mockResolvedValueOnce(
      new Response(null, { status: 204 })
    )

    const { logout } = useChatApi()
    const ok = await logout()

    expect(ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/logout')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(init.headers['X-XSRF-TOKEN']).toBe('csrf-token-value')
  })

  it('omits X-XSRF-TOKEN when no CSRF cookie is set', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    const { logout } = useChatApi()
    await logout()

    const [, init] = fetchMock.mock.calls[0]!
    expect(init.headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('returns false for a non-2xx response', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('Forbidden', { status: 403 })
    )

    const { logout } = useChatApi()
    expect(await logout()).toBe(false)
  })

  it('returns false when fetch rejects', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('network error'))

    const { logout } = useChatApi()
    expect(await logout()).toBe(false)
  })

  it('posts to the runbook export endpoint with csrf protection', async () => {
    ;(globalThis as unknown as { document: { cookie: string } }).document.cookie =
      'XSRF-TOKEN=csrf-token-value'
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ filename: 'incident.md', markdown: '# Incident' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    )

    const { exportRunbook } = useChatApi()
    const result = await exportRunbook('chat-123')

    expect(result).toEqual({ filename: 'incident.md', markdown: '# Incident' })
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/chats/chat-123/export/runbook')
    expect(init.method).toBe('POST')
    expect(init.headers['X-XSRF-TOKEN']).toBe('csrf-token-value')
  })
})

describe('normalizeAvailability', () => {
  it('accepts the three known availability flags', () => {
    expect(normalizeAvailability('ALWAYS')).toBe('ALWAYS')
    expect(normalizeAvailability('BYOK_ONLY')).toBe('BYOK_ONLY')
    expect(normalizeAvailability('UPGRADE_REQUIRED')).toBe('UPGRADE_REQUIRED')
  })

  it('is case-insensitive', () => {
    expect(normalizeAvailability('upgrade_required')).toBe('UPGRADE_REQUIRED')
  })

  it('falls back to ALWAYS for unknown / null / missing values', () => {
    expect(normalizeAvailability(null)).toBe('ALWAYS')
    expect(normalizeAvailability(undefined)).toBe('ALWAYS')
    expect(normalizeAvailability('')).toBe('ALWAYS')
    expect(normalizeAvailability('something_else')).toBe('ALWAYS')
    expect(normalizeAvailability(42)).toBe('ALWAYS')
  })
})

describe('normalizeRequiredPlan', () => {
  it('accepts PREMIUM and MAX (any case)', () => {
    expect(normalizeRequiredPlan('PREMIUM')).toBe('PREMIUM')
    expect(normalizeRequiredPlan('max')).toBe('MAX')
  })

  it('returns null for unknown or missing values', () => {
    expect(normalizeRequiredPlan(null)).toBeNull()
    expect(normalizeRequiredPlan(undefined)).toBeNull()
    expect(normalizeRequiredPlan('FREE')).toBeNull()
    expect(normalizeRequiredPlan(123)).toBeNull()
  })
})

describe('isModelLocked', () => {
  it('returns true only for UPGRADE_REQUIRED models', () => {
    expect(isModelLocked({ availability: 'UPGRADE_REQUIRED' })).toBe(true)
    expect(isModelLocked({ availability: 'ALWAYS' })).toBe(false)
    expect(isModelLocked({ availability: 'BYOK_ONLY' })).toBe(false)
    expect(isModelLocked({ availability: null })).toBe(false)
    expect(isModelLocked({})).toBe(false)
  })
})
