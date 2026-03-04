import { describe, it, expect } from 'vitest'
import { extractAssistantText, parseAttachmentContext } from '../useChatStore'

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
