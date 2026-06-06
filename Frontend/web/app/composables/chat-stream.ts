import type { Provider } from '~/utils/model-utils'

export type StreamEvent = {
  type: 'text' | 'reasoning' | 'done' | 'error'
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
 * SSE streams are complete once the backend emits either a final `done` event
 * or an `error` event that already contains the terminal failure message.
 */
export function isTerminalStreamEvent(event: StreamEvent) {
  return event.type === 'done' || event.type === 'error'
}
