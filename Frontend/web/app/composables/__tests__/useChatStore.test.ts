import { describe, it, expect } from 'vitest'
import type { UIMessage } from 'ai'
import type { ModelOption, ToolCallLog } from '../useChatApi'
import { favoriteModelKey } from '../../utils/model-utils'
import { formatRatePerMillion } from '../../utils/format'
import { appendTextToMessage, calculateMessageCost, estimateAssistantOutputTokens, estimateConversationTokens, estimatePreSendCost, estimateTextTokens, extractAssistantText, extractTokenUsage, filterToolCallLogs, formatCost, parseAttachmentContext, parsePersistedMessageId, resolveStreamOutcome } from '../chat-helpers'
import { isCurrentConversationRequest } from '../useChatStore'

describe('stream request generations', () => {
  it('rejects a stale controller after cancel and restart', () => {
    const first = new AbortController()
    const second = new AbortController()
    const conversation = { activeRequest: first, requestGeneration: 1 }

    expect(isCurrentConversationRequest(conversation, first, 1)).toBe(true)
    conversation.activeRequest = second
    conversation.requestGeneration = 2

    expect(isCurrentConversationRequest(conversation, first, 1)).toBe(false)
    expect(isCurrentConversationRequest(conversation, second, 2)).toBe(true)
  })
})

describe('extractAssistantText', () => {
  it('extracts text from a plain string response', () => {
    expect(extractAssistantText('hello')).toBe('hello')
  })

  it('extracts from { text: "..." }', () => {
    expect(extractAssistantText({ text: 'hello' })).toBe('hello')
  })

  it('extracts from { content: "..." }', () => {
    expect(extractAssistantText({ content: 'hello' })).toBe('hello')
  })

  it('extracts from nested { result: { output: { text: "..." } } }', () => {
    expect(extractAssistantText({ result: { output: { text: 'hello' } } })).toBe('hello')
  })

  it('extracts from { results: [{ text: "..." }] }', () => {
    expect(extractAssistantText({ results: [{ text: 'hello' }] })).toBe('hello')
  })

  it('extracts from array of candidates', () => {
    expect(extractAssistantText([{ text: 'hello' }])).toBe('hello')
  })

  it('returns fallback for null response', () => {
    expect(extractAssistantText(null)).toBe('No response received.')
  })

  it('returns fallback for undefined response', () => {
    expect(extractAssistantText(undefined)).toBe('No response received.')
  })

  it('returns fallback for empty object', () => {
    expect(extractAssistantText({})).toBe('No response received.')
  })

  it('trims whitespace from extracted text', () => {
    expect(extractAssistantText('  hello  ')).toBe('hello')
  })
})

describe('parseAttachmentContext', () => {
  it('parses single mime type', () => {
    expect(parseAttachmentContext('attachments:image/png')).toEqual(['image/png'])
  })

  it('parses multiple mime types', () => {
    expect(parseAttachmentContext('attachments:image/png,application/pdf')).toEqual([
      'image/png',
      'application/pdf'
    ])
  })

  it('returns empty array for non-attachment context', () => {
    expect(parseAttachmentContext('something else')).toEqual([])
  })

  it('returns empty array for empty attachments', () => {
    expect(parseAttachmentContext('attachments:')).toEqual([])
  })

  it('trims whitespace from mime types', () => {
    expect(parseAttachmentContext('attachments: image/png , application/pdf ')).toEqual([
      'image/png',
      'application/pdf'
    ])
  })

  it('filters out empty entries', () => {
    expect(parseAttachmentContext('attachments:image/png,,application/pdf')).toEqual([
      'image/png',
      'application/pdf'
    ])
  })
})

describe('parsePersistedMessageId', () => {
  it('extracts the backend id from persisted message ids', () => {
    expect(parsePersistedMessageId('msg-42')).toBe(42)
  })

  it('returns null for temporary or invalid ids', () => {
    expect(parsePersistedMessageId('tmp-42')).toBeNull()
    expect(parsePersistedMessageId('msg-abc')).toBeNull()
    expect(parsePersistedMessageId(undefined)).toBeNull()
  })
})

describe('filterToolCallLogs', () => {
  const logs: ToolCallLog[] = [
    { toolName: 'BraveSearchTool.searchWeb', messageId: 1, inputPayload: '{}', outputPayload: 'a' },
    { toolName: 'CurrentDateTimeTool.getCurrentDateTime', messageId: 1, inputPayload: '{}', outputPayload: 'b' }
  ]

  it('returns all logs for an empty filter', () => {
    expect(filterToolCallLogs(logs, '')).toEqual(logs)
  })

  it('filters tool calls by tool name case-insensitively', () => {
    expect(filterToolCallLogs(logs, 'search')).toEqual([logs[0]])
    expect(filterToolCallLogs(logs, 'datetime')).toEqual([logs[1]])
  })
})

describe('extractTokenUsage', () => {
  it('extracts token usage from response with all fields', () => {
    const response = { message: 'hello', promptTokens: 100, generationTokens: 50, totalTokens: 150 }
    expect(extractTokenUsage(response)).toEqual({
      promptTokens: 100,
      generationTokens: 50,
      totalTokens: 150
    })
  })

  it('returns null for null response', () => {
    expect(extractTokenUsage(null)).toBeNull()
  })

  it('returns null when all token fields are null', () => {
    const response = { message: 'hello', promptTokens: null, generationTokens: null, totalTokens: null }
    expect(extractTokenUsage(response)).toBeNull()
  })

  it('returns null when all token fields are undefined', () => {
    const response = { message: 'hello' }
    expect(extractTokenUsage(response)).toBeNull()
  })

  it('handles partial token usage (only some fields present)', () => {
    const response = { message: 'hello', promptTokens: 100, generationTokens: null, totalTokens: null }
    expect(extractTokenUsage(response)).toEqual({
      promptTokens: 100,
      generationTokens: null,
      totalTokens: null
    })
  })

  it('handles zero token values', () => {
    const response = { message: 'hello', promptTokens: 0, generationTokens: 0, totalTokens: 0 }
    expect(extractTokenUsage(response)).toEqual({
      promptTokens: 0,
      generationTokens: 0,
      totalTokens: 0
    })
  })
})

describe('favoriteModelKey', () => {
  it('generates a key from provider and model value', () => {
    expect(favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4o', label: 'GPT-4o' })).toBe('OPENAI:gpt-4o')
  })

  it('generates distinct keys for different providers', () => {
    const openaiKey = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4', label: 'GPT-4' })
    const claudeKey = favoriteModelKey({ provider: 'CLAUDE', value: 'gpt-4', label: 'GPT-4' })
    expect(openaiKey).not.toBe(claudeKey)
  })

  it('generates distinct keys for different model values', () => {
    const gpt4Key = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4', label: 'GPT-4' })
    const gpt4oKey = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4o', label: 'GPT-4o' })
    expect(gpt4Key).not.toBe(gpt4oKey)
  })
})

describe('appendTextToMessage', () => {
  it('returns a new message with appended text for streaming updates', () => {
    const original = {
      id: 'msg-1',
      role: 'assistant',
      parts: [{ type: 'text', text: 'Hello' }]
    } as unknown as UIMessage

    const updated = appendTextToMessage(original, ' world')

    expect(updated).toEqual({
      id: 'msg-1',
      role: 'assistant',
      parts: [{ type: 'text', text: 'Hello world' }]
    })
    expect(updated).not.toBe(original)
    expect(updated.parts).not.toBe(original.parts)
  })

  it('preserves non-text parts while appending streamed text', () => {
    const original = {
      id: 'msg-2',
      role: 'assistant',
      parts: [
        { type: 'text', text: 'See' },
        { type: 'file', mediaType: 'image/png', url: '' }
      ]
    } as unknown as UIMessage

    const updated = appendTextToMessage(original, ' attached image')

    expect(updated.parts).toEqual([
      { type: 'text', text: 'See attached image' },
      { type: 'file', mediaType: 'image/png', url: '' }
    ])
  })
})

describe('resolveStreamOutcome', () => {
  it('marks completed streams as ready', () => {
    expect(resolveStreamOutcome({
      receivedDone: true,
      receivedError: false,
      receivedContent: true
    }, null)).toEqual({
      status: 'ready',
      error: null
    })
  })

  it('keeps SSE error events in error state', () => {
    expect(resolveStreamOutcome({
      receivedDone: false,
      receivedError: true,
      receivedContent: true
    }, 'Provider timed out')).toEqual({
      status: 'error',
      error: 'Provider timed out'
    })
  })

  it('surfaces interrupted streams with a partial-response message', () => {
    expect(resolveStreamOutcome({
      receivedDone: false,
      receivedError: false,
      receivedContent: true
    }, null)).toEqual({
      status: 'error',
      error: 'The response stream was interrupted before completion. Partial response shown.'
    })
  })
})

describe('calculateMessageCost', () => {
  it('calculates cost from token usage and model pricing', () => {
    const usage = { promptTokens: 1000, generationTokens: 500, totalTokens: 1500 }
    const model = { promptPrice: '0.000005', completionPrice: '0.000015', requestPrice: '0' }
    const cost = calculateMessageCost(usage, model)
    expect(cost).not.toBeNull()
    expect(cost!.promptCost).toBeCloseTo(0.005)
    expect(cost!.completionCost).toBeCloseTo(0.0075)
    expect(cost!.requestCost).toBeNull() // requestPrice is 0
    expect(cost!.totalCost).toBeCloseTo(0.0125)
  })

  it('returns null when usage is null', () => {
    const model = { promptPrice: '0.000005', completionPrice: '0.000015' }
    expect(calculateMessageCost(null, model)).toBeNull()
  })

  it('returns null when model is null', () => {
    const usage = { promptTokens: 100, generationTokens: 50, totalTokens: 150 }
    expect(calculateMessageCost(usage, null)).toBeNull()
  })

  it('returns null when model has no pricing fields', () => {
    const usage = { promptTokens: 100, generationTokens: 50, totalTokens: 150 }
    const model = { promptPrice: null, completionPrice: null, requestPrice: null }
    expect(calculateMessageCost(usage, model)).toBeNull()
  })

  it('handles model with only prompt pricing', () => {
    const usage = { promptTokens: 1000, generationTokens: 500, totalTokens: 1500 }
    const model = { promptPrice: '0.000005', completionPrice: null, requestPrice: null }
    const cost = calculateMessageCost(usage, model)
    expect(cost).not.toBeNull()
    expect(cost!.promptCost).toBeCloseTo(0.005)
    expect(cost!.completionCost).toBeNull()
    expect(cost!.totalCost).toBeCloseTo(0.005)
  })

  it('includes per-request cost when requestPrice is non-zero', () => {
    const usage = { promptTokens: 100, generationTokens: 50, totalTokens: 150 }
    const model = { promptPrice: '0.000005', completionPrice: '0.000015', requestPrice: '0.001' }
    const cost = calculateMessageCost(usage, model)
    expect(cost).not.toBeNull()
    expect(cost!.requestCost).toBeCloseTo(0.001)
    expect(cost!.totalCost).toBeCloseTo(0.0005 + 0.00075 + 0.001)
  })

  it('handles zero token counts', () => {
    const usage = { promptTokens: 0, generationTokens: 0, totalTokens: 0 }
    const model = { promptPrice: '0.000005', completionPrice: '0.000015' }
    const cost = calculateMessageCost(usage, model)
    expect(cost).not.toBeNull()
    expect(cost!.totalCost).toBe(0)
  })
})

describe('estimateTextTokens', () => {
  it('returns zero for blank input', () => {
    expect(estimateTextTokens('   ')).toBe(0)
  })

  it('estimates roughly one token per four characters', () => {
    expect(estimateTextTokens('abcdefgh')).toBe(2)
  })
})

describe('estimateConversationTokens', () => {
  it('counts chat history, the pending prompt, and attachment overhead', () => {
    const messages = [
      { parts: [{ type: 'text', text: 'Hello there' }] },
      { parts: [{ type: 'text', text: 'General Kenobi' }] }
    ] as Array<Pick<UIMessage, 'parts'>>

    expect(estimateConversationTokens(messages, 'What now?', 2)).toBe(46)
  })
})

describe('estimateAssistantOutputTokens', () => {
  it('averages prior assistant generation token usage when available', () => {
    const messages = [
      { id: 'msg-1', role: 'assistant', parts: [{ type: 'text', text: 'First reply' }] },
      { id: 'msg-2', role: 'assistant', parts: [{ type: 'text', text: 'Second reply' }] }
    ] as Array<Pick<UIMessage, 'id' | 'role' | 'parts'>>

    expect(estimateAssistantOutputTokens(messages, {
      'msg-1': { promptTokens: 10, generationTokens: 120, totalTokens: 130 },
      'msg-2': { promptTokens: 12, generationTokens: 180, totalTokens: 192 }
    })).toBe(150)
  })

  it('falls back to assistant text estimates and a default for empty chats', () => {
    expect(estimateAssistantOutputTokens([], {})).toBe(256)
    expect(estimateAssistantOutputTokens([
      { id: 'msg-3', role: 'assistant', parts: [{ type: 'text', text: '12345678' }] }
    ] as Array<Pick<UIMessage, 'id' | 'role' | 'parts'>>)).toBe(2)
  })
})

describe('estimatePreSendCost', () => {
  it('calculates estimated input and output costs from chat history and model pricing', () => {
    const messages = [
      { id: 'msg-1', role: 'user', parts: [{ type: 'text', text: 'Hello there' }] },
      { id: 'msg-2', role: 'assistant', parts: [{ type: 'text', text: 'General Kenobi' }] }
    ] as Array<Pick<UIMessage, 'id' | 'role' | 'parts'>>
    const estimate = estimatePreSendCost(
      messages,
      'What now?',
      { promptPrice: '0.000005', completionPrice: '0.000015', requestPrice: '0.1' } satisfies Pick<ModelOption, 'promptPrice' | 'completionPrice' | 'requestPrice'>,
      {
        'msg-2': { promptTokens: 10, generationTokens: 120, totalTokens: 130 }
      }
    )

    expect(estimate).not.toBeNull()
    expect(estimate!.inputTokens).toBe(22)
    expect(estimate!.estimatedOutputTokens).toBe(120)
    expect(estimate!.promptCost).toBeCloseTo(0.00011)
    expect(estimate!.completionCost).toBeCloseTo(0.0018)
    expect(estimate!.totalCost).toBeCloseTo(0.00191)
  })

  it('returns null when the model has no token pricing', () => {
    expect(estimatePreSendCost([], 'Hello', { promptPrice: null, completionPrice: null })).toBeNull()
  })
})

describe('formatCost', () => {
  it('formats zero cost', () => {
    expect(formatCost(0)).toBe('$0.00')
  })

  it('formats normal cost with 2 decimal places', () => {
    expect(formatCost(0.05)).toBe('$0.05')
  })

  it('formats sub-cent cost with 4 decimal places', () => {
    expect(formatCost(0.005)).toBe('$0.0050')
  })

  it('formats very small cost in scientific notation', () => {
    expect(formatCost(0.00005)).toBe('$5.0e-5')
  })

  it('formats whole dollar amounts', () => {
    expect(formatCost(1.23)).toBe('$1.23')
  })

  it('returns dash for NaN', () => {
    expect(formatCost(NaN)).toBe('—')
  })

  it('returns dash for Infinity', () => {
    expect(formatCost(Infinity)).toBe('—')
  })
})

describe('formatRatePerMillion', () => {
  it('returns null for null input', () => {
    expect(formatRatePerMillion(null)).toBeNull()
  })

  it('returns null for undefined input', () => {
    expect(formatRatePerMillion(undefined)).toBeNull()
  })

  it('returns null for empty string', () => {
    expect(formatRatePerMillion('')).toBeNull()
  })

  it('returns "$0" for zero price', () => {
    expect(formatRatePerMillion('0')).toBe('$0')
  })

  it('formats a typical prompt price', () => {
    expect(formatRatePerMillion('0.000005')).toBe('$5.00')
  })

  it('formats a sub-dollar per-million price with 4 decimals', () => {
    expect(formatRatePerMillion('0.0000001')).toBe('$0.1000')
  })

  it('returns null for non-numeric string', () => {
    expect(formatRatePerMillion('abc')).toBeNull()
  })
})
