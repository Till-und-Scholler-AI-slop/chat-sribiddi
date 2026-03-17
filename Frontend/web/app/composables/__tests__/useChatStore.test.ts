import { describe, it, expect } from 'vitest'
import { appendTextToMessage, extractAssistantText, extractTokenUsage, favoriteModelKey, parseAttachmentContext, resolveStreamOutcome } from '../useChatStore'

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
    const response = { message: 'hello' } as any
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
    expect(favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4o', label: 'GPT-4o' } as any)).toBe('OPENAI:gpt-4o')
  })

  it('generates distinct keys for different providers', () => {
    const openaiKey = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4', label: 'GPT-4' } as any)
    const claudeKey = favoriteModelKey({ provider: 'CLAUDE', value: 'gpt-4', label: 'GPT-4' } as any)
    expect(openaiKey).not.toBe(claudeKey)
  })

  it('generates distinct keys for different model values', () => {
    const gpt4Key = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4', label: 'GPT-4' } as any)
    const gpt4oKey = favoriteModelKey({ provider: 'OPENAI', value: 'gpt-4o', label: 'GPT-4o' } as any)
    expect(gpt4Key).not.toBe(gpt4oKey)
  })
})

describe('appendTextToMessage', () => {
  it('returns a new message with appended text for streaming updates', () => {
    const original = {
      id: 'msg-1',
      role: 'assistant',
      parts: [{ type: 'text', text: 'Hello' }]
    } as any

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
    } as any

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
