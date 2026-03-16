import { describe, it, expect } from 'vitest'
import { mapModelOption, normalizeBaseUrl } from '../useChatApi'

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

describe('mapModelOption', () => {
  it('preserves provider metadata for model catalog pages', () => {
    expect(
      mapModelOption('OPENROUTER', {
        id: 'openai/gpt-4.1',
        label: 'GPT-4.1',
        description: 'OpenRouter hosted model',
        contextWindow: 1_000_000,
        pricing: {
          prompt: '0.000002',
          completion: '0.000008'
        }
      })
    ).toEqual({
      label: 'GPT-4.1',
      value: 'openai/gpt-4.1',
      provider: 'OPENROUTER',
      description: 'OpenRouter hosted model',
      contextWindow: 1_000_000,
      pricing: {
        prompt: '0.000002',
        completion: '0.000008'
      }
    })
  })
})
