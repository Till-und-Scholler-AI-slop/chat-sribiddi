import { describe, it, expect } from 'vitest'
import type { AvailableTool } from '../useToolApi'

describe('AvailableTool', () => {
  it('accepts a fully-populated tool object', () => {
    const tool: AvailableTool = {
      name: 'calculator',
      displayName: 'Calculator',
      description: 'Performs arithmetic',
      enabled: true
    }
    expect(tool.name).toBe('calculator')
    expect(tool.enabled).toBe(true)
  })

  it('accepts a disabled tool (e.g. brave-search without an API key)', () => {
    const tool: AvailableTool = {
      name: 'brave-search',
      displayName: 'Brave Search',
      description: 'Web search',
      enabled: false
    }
    expect(tool.enabled).toBe(false)
  })
})
