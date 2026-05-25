import { describe, expect, it } from 'vitest'
import { friendlyErrorKey } from '../errors'

describe('friendlyErrorKey', () => {
  it('maps common network failures', () => {
    expect(friendlyErrorKey('Failed to fetch').key).toBe('errors.backendUnreachable')
    expect(friendlyErrorKey('ERR_CONNECTION_REFUSED').key).toBe('errors.backendUnreachable')
  })

  it('maps abort errors', () => {
    expect(friendlyErrorKey(new Error('Request abort')).key).toBe('errors.aborted')
  })

  it('maps HTTP auth and permission errors', () => {
    expect(friendlyErrorKey('401 Unauthorized').key).toBe('errors.unauthorized')
    expect(friendlyErrorKey('Forbidden CSRF token mismatch').key).toBe('errors.forbidden')
  })

  it('maps common backend status and api key errors', () => {
    expect(friendlyErrorKey('404 Not Found').key).toBe('errors.notFound')
    expect(friendlyErrorKey('413 Payload Too Large').key).toBe('errors.payloadTooLarge')
    expect(friendlyErrorKey('rate limit exceeded').key).toBe('errors.rateLimit')
    expect(friendlyErrorKey('{"reason":"DAILY_CAP_EXCEEDED"}').key).toBe('errors.dailyCapExceeded')
    expect(friendlyErrorKey('{"reason":"MODEL_NOT_ALLOWED"}').key).toBe('errors.modelNotAllowed')
    expect(friendlyErrorKey('{"reason":"PARALLEL_STREAMS_EXCEEDED"}').key)
      .toBe('errors.parallelStreamsExceeded')
    expect(friendlyErrorKey('{"reason":"TOOL_NOT_ALLOWED"}').key).toBe('errors.toolNotAllowed')
    expect(friendlyErrorKey('billing unavailable').key).toBe('errors.billingUnavailable')
    expect(friendlyErrorKey('Request cancelled').key).toBe('errors.cancelled')
    expect(friendlyErrorKey('{"reason":"MANAGED_PROVIDER_UNCONFIGURED"}').key)
      .toBe('errors.managedProviderUnconfigured')
    expect(friendlyErrorKey('Unsupported document type').key).toBe('errors.unsupportedDocumentType')
    expect(friendlyErrorKey('Document contains no extractable text').key).toBe('errors.documentNoText')
    expect(friendlyErrorKey('Encrypted PDFs are not supported').key)
      .toBe('errors.encryptedPdfUnsupported')
    expect(friendlyErrorKey('500 Internal Server Error').key).toBe('errors.serverError')
    expect(friendlyErrorKey('API key invalid').key).toBe('errors.apiKey')
  })

  it('returns no key for short unknown messages and exposes the raw text', () => {
    const result = friendlyErrorKey('Etwas passt nicht')
    expect(result.key).toBeNull()
    expect(result.raw).toBe('Etwas passt nicht')
  })

  it('falls back for empty or very long messages', () => {
    expect(friendlyErrorKey('').key).toBe('errors.fallback')
    expect(friendlyErrorKey('x'.repeat(200)).key).toBe('errors.fallback')
  })
})
