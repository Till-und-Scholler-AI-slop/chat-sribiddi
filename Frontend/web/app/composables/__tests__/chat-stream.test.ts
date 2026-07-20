import { afterEach, describe, expect, it, vi } from 'vitest'
import { createFrameBatchedStreamDispatcher, SseEventParser } from '../chat-stream'

describe('SseEventParser', () => {
  it('emits a complete event from a single chunk', () => {
    const parser = new SseEventParser()
    const events = parser.push('event: text\ndata: {"type":"text","content":"hi"}\n\n')
    expect(events).toEqual([{ type: 'text', content: 'hi' }])
  })

  it('keeps an incomplete event in the buffer and emits on the next push', () => {
    const parser = new SseEventParser()
    const first = parser.push('event: text\ndata: {"type":"text","conte')
    expect(first).toEqual([])
    const second = parser.push('nt":"hi"}\n\n')
    expect(second).toEqual([{ type: 'text', content: 'hi' }])
  })

  it('emits multiple events split across pushes', () => {
    const parser = new SseEventParser()
    const first = parser.push('event: text\ndata: {"type":"text","content":"a"}\n\nevent: te')
    expect(first).toEqual([{ type: 'text', content: 'a' }])
    const second = parser.push('xt\ndata: {"type":"text","content":"b"}\n\n')
    expect(second).toEqual([{ type: 'text', content: 'b' }])
  })

  it('flush() returns a trailing event that lacked a terminator', () => {
    const parser = new SseEventParser()
    parser.push('event: text\ndata: {"type":"text","content":"a"}')
    const flushed = parser.flush()
    expect(flushed).toEqual([{ type: 'text', content: 'a' }])
  })

  it('flush() returns nothing for an empty buffer', () => {
    const parser = new SseEventParser()
    expect(parser.flush()).toEqual([])
  })

  it('skips events with malformed JSON rather than throwing', () => {
    const parser = new SseEventParser()
    const events = parser.push('data: not-json\n\ndata: {"type":"text","content":"ok"}\n\n')
    expect(events).toEqual([{ type: 'text', content: 'ok' }])
  })

  it('throws when the buffer exceeds the configured limit', () => {
    const parser = new SseEventParser({ maxBufferBytes: 32 })
    expect(() => parser.push('x'.repeat(64))).toThrow(/buffer exceeded/i)
  })

  it('handles CRLF line endings', () => {
    const parser = new SseEventParser()
    const events = parser.push('event: text\r\ndata: {"type":"text","content":"hi"}\r\n\r\n')
    expect(events).toEqual([{ type: 'text', content: 'hi' }])
  })

  it('reset() clears any pending buffered data', () => {
    const parser = new SseEventParser()
    parser.push('event: text\ndata: {"type":"text","content":"unfinished')
    parser.reset()
    expect(parser.push('event: text\ndata: {"type":"text","content":"fresh"}\n\n')).toEqual([
      { type: 'text', content: 'fresh' }
    ])
  })
})

describe('createFrameBatchedStreamDispatcher', () => {
  afterEach(() => vi.useRealTimers())

  it('coalesces adjacent deltas and flushes before terminal events', async () => {
    vi.useFakeTimers()
    const received: Array<{ type: string; content?: string | null }> = []
    const dispatcher = createFrameBatchedStreamDispatcher(event => received.push(event))

    dispatcher.push({ type: 'text', content: 'Hello' })
    dispatcher.push({ type: 'text', content: ' world' })
    expect(received).toEqual([])

    dispatcher.push({ type: 'done' })
    expect(received).toEqual([
      { type: 'text', content: 'Hello world' },
      { type: 'done' }
    ])
    await vi.runAllTimersAsync()
    expect(received).toHaveLength(2)
  })
})
