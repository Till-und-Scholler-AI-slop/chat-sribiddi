import { describe, it, expect } from 'vitest'
import { buildToolHeaders } from '../useToolApi'

describe('buildToolHeaders', () => {
  it('includes Content-Type and X-Chat-User-Id for read requests', () => {
    const h = buildToolHeaders('user-123', null, false)
    expect(h['Content-Type']).toBe('application/json')
    expect(h['X-Chat-User-Id']).toBe('user-123')
    expect(h['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('adds X-XSRF-TOKEN when mutating and csrf is present', () => {
    const h = buildToolHeaders('user-123', 'csrf-abc', true)
    expect(h['X-XSRF-TOKEN']).toBe('csrf-abc')
  })

  it('omits X-XSRF-TOKEN when mutating but csrf is null', () => {
    const h = buildToolHeaders('user-123', null, true)
    expect(h['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('omits X-XSRF-TOKEN for read requests even when csrf is present', () => {
    const h = buildToolHeaders('user-123', 'csrf-abc', false)
    expect(h['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('uses the provided userId in X-Chat-User-Id', () => {
    const h = buildToolHeaders('alice@example.com', null)
    expect(h['X-Chat-User-Id']).toBe('alice@example.com')
  })
})
