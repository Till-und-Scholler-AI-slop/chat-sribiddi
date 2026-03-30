import { describe, it, expect } from 'vitest'
import {
  isTerminalStreamEvent,
  modelEndpointPath,
  normalizeBaseUrl,
  parseSseEvents,
  REQUEST_USER_ID_HEADER,
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
