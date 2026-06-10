import type { Provider } from '~/utils/model-utils'

export type StreamEvent = {
  type:
    | 'text'
    | 'reasoning'
    | 'done'
    | 'error'
    | 'tool_call_started'
    | 'tool_call_completed'
    | 'tool_call_failed'
  content?: string | null
  promptTokens?: number | null
  generationTokens?: number | null
  totalTokens?: number | null
  chatTitle?: string | null
  messageId?: number | null
  hasToolCalls?: boolean | null
  provider?: Provider | null
  modelId?: string | null
  statusCode?: number | null
  errorType?: string | null
  receivedBytes?: number | null
  incomplete?: boolean | null
  clientId?: string | null
  toolName?: string | null
  inputPayload?: string | null
  outputPayload?: string | null
  startedAt?: string | null
  durationMs?: number | null
  contextExcerpt?: string | null
  status?: 'RUNNING' | 'SUCCESS' | 'ERROR' | string | null
  errorMessage?: string | null
  displayType?: string | null
}

function parseOptionalBoolean(value: unknown): boolean | null {
  if (typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return null
}

function normalizeParsedStreamEvent(event: StreamEvent): StreamEvent {
  if (event.statusCode != null) {
    const parsed =
      typeof event.statusCode === 'number'
        ? event.statusCode
        : Number.parseInt(String(event.statusCode), 10)
    event.statusCode = Number.isFinite(parsed) ? parsed : null
  }

  if (event.errorType != null) {
    event.errorType = String(event.errorType).toUpperCase()
  }

  if (event.receivedBytes != null) {
    const parsed =
      typeof event.receivedBytes === 'number'
        ? event.receivedBytes
        : Number.parseInt(String(event.receivedBytes), 10)
    event.receivedBytes = Number.isFinite(parsed) ? parsed : null
  }

  if (event.incomplete != null) {
    event.incomplete = parseOptionalBoolean(event.incomplete)
  }

  if (event.durationMs != null) {
    const parsed =
      typeof event.durationMs === 'number'
        ? event.durationMs
        : Number.parseInt(String(event.durationMs), 10)
    event.durationMs = Number.isFinite(parsed) ? parsed : null
  }

  return event
}

export function normalizeSsePayload(raw: string) {
  return raw.replace(/\r\n?/g, '\n')
}

/**
 * Parses a raw SSE chunk into individual stream events.
 * Each SSE message is delimited by a blank line and may contain
 * `event:` and `data:` fields.
 */
export function parseSseEvents(raw: string): StreamEvent[] {
  const events: StreamEvent[] = []
  const blocks = normalizeSsePayload(raw).split(/\n\n+/)

  for (const block of blocks) {
    const trimmedBlock = block.trim()
    if (!trimmedBlock) continue

    const dataLines: string[] = []
    for (const line of trimmedBlock.split('\n')) {
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }

    const dataLine = dataLines.join('\n')
    if (!dataLine) continue

    try {
      const parsed = normalizeParsedStreamEvent(JSON.parse(dataLine) as StreamEvent)
      if (parsed && parsed.type) {
        events.push(parsed)
      }
    } catch {
      // skip malformed JSON
    }
  }

  return events
}

/**
 * A stateful SSE parser that survives partial chunks: callers push a chunk,
 * and the parser emits any complete events that have arrived since the last
 * push. This is the recommended parser for streaming readers because it
 * handles events split across chunk boundaries without forcing the caller to
 * maintain their own buffer and splitter.
 *
 * The parser is intentionally strict about the blank-line delimiter (the
 * spec-mandated separator) so that a single SSE event containing newlines in
 * its `data:` payload is never accidentally split into two events. A
 * configurable cap prevents a hostile server from filling the buffer with
 * data that never terminates.
 */
export class SseEventParser {
  private buffer = ''
  private readonly maxBufferBytes: number

  constructor(options: { maxBufferBytes?: number } = {}) {
    this.maxBufferBytes = options.maxBufferBytes ?? 256 * 1024
  }

  push(chunk: string): StreamEvent[] {
    if (chunk) this.buffer += normalizeSsePayload(chunk)
    if (this.buffer.length > this.maxBufferBytes) {
      throw new Error('SSE stream buffer exceeded the configured limit')
    }
    const events: StreamEvent[] = []
    let cursor = 0
    while (true) {
      const terminator = this.buffer.indexOf('\n\n', cursor)
      if (terminator === -1) {
        // Keep an unterminated trailing line that begins with the SSE
        // field-prefix characters but is not yet a complete event.
        const tail = this.buffer.slice(cursor)
        if (tail.length === this.buffer.length) break
        this.buffer = tail
        return events
      }
      const raw = this.buffer.slice(cursor, terminator)
      cursor = terminator + 2
      const event = parseSingleSseBlock(raw)
      if (event) events.push(event)
    }
    this.buffer = this.buffer.slice(cursor)
    return events
  }

  /** Flush any trailing data left in the buffer (e.g. on stream end). */
  flush(): StreamEvent[] {
    if (!this.buffer.trim()) {
      this.buffer = ''
      return []
    }
    const event = parseSingleSseBlock(this.buffer)
    this.buffer = ''
    return event ? [event] : []
  }

  reset() {
    this.buffer = ''
  }
}

function parseSingleSseBlock(block: string): StreamEvent | null {
  const trimmedBlock = block.trim()
  if (!trimmedBlock) return null
  const dataLines: string[] = []
  for (const line of trimmedBlock.split('\n')) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }
  const dataLine = dataLines.join('\n')
  if (!dataLine) return null
  try {
    const parsed = normalizeParsedStreamEvent(JSON.parse(dataLine) as StreamEvent)
    return parsed && parsed.type ? parsed : null
  } catch {
    return null
  }
}

/**
 * SSE streams are complete once the backend emits either a final `done` event
 * or an `error` event that already contains the terminal failure message.
 */
export function isTerminalStreamEvent(event: StreamEvent) {
  return event.type === 'done' || event.type === 'error'
}

export function isToolCallStreamEvent(event: StreamEvent): event is StreamEvent & {
  type: 'tool_call_started' | 'tool_call_completed' | 'tool_call_failed'
} {
  return (
    event.type === 'tool_call_started'
    || event.type === 'tool_call_completed'
    || event.type === 'tool_call_failed'
  )
}
