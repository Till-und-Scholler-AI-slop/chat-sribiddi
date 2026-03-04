import { describe, it, expect } from 'vitest'
import { extractAssistantText } from '../useChatStore'

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
