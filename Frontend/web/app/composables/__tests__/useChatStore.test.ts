import { describe, it, expect } from 'vitest'
import { extractAssistantText, extractTokenUsage, parseAttachmentContext } from '../useChatStore'

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
