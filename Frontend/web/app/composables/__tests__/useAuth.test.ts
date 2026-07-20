import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

describe('useAuth', () => {
  beforeEach(() => {
    vi.resetModules()
    const app = {}
    vi.stubGlobal('useNuxtApp', () => app)
    const states = new Map<string, ReturnType<typeof ref>>()
    vi.stubGlobal('useState', (key: string, init: () => unknown) => {
      if (!states.has(key)) {
        states.set(key, ref(init()))
      }
      return states.get(key)
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shares an in-flight auth probe between concurrent callers', async () => {
    let resolveSession!: (value: boolean) => void
    const checkSession = vi.fn(() => new Promise<boolean>(resolve => {
      resolveSession = resolve
    }))
    vi.stubGlobal('useChatApi', () => ({ checkSession }))
    const { useAuth } = await import('../useAuth')

    const firstAuth = useAuth()
    const first = firstAuth.checkAuth()
    const second = useAuth().checkAuth()

    expect(checkSession).toHaveBeenCalledTimes(1)
    resolveSession(true)
    await expect(first).resolves.toBe('authenticated')
    await expect(second).resolves.toBe('authenticated')
    expect(firstAuth.status.value).toBe('authenticated')
  })

  it('does not let a stale auth probe undo logout', async () => {
    let resolveSession!: (value: boolean) => void
    const checkSession = vi.fn(() => new Promise<boolean>(resolve => {
      resolveSession = resolve
    }))
    const logout = vi.fn().mockResolvedValue(true)
    vi.stubGlobal('useChatApi', () => ({ checkSession, logout }))
    const { useAuth } = await import('../useAuth')

    const auth = useAuth()
    const pendingCheck = auth.checkAuth()
    await auth.logout()
    resolveSession(true)
    await pendingCheck

    expect(auth.status.value).toBe('unauthenticated')
  })
})
