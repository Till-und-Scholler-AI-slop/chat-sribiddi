import { describe, it, expect } from 'vitest'
import { modelEndpointPath, normalizeBaseUrl } from '../useChatApi'

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
