type AuthState = 'unknown' | 'authenticated' | 'unauthenticated'

let authCheckPromise: Promise<AuthState> | null = null

export function useAuth() {
  const status = useState<AuthState>('auth-status', () => 'unknown')
  const checking = useState<boolean>('auth-checking', () => false)

  const checkAuth = async () => {
    if (authCheckPromise) return authCheckPromise
    checking.value = true
    authCheckPromise = (async () => {
      const { checkSession } = useChatApi()
      try {
        const ok = await checkSession()
        status.value = ok ? 'authenticated' : 'unauthenticated'
      } catch {
        status.value = 'unauthenticated'
      }
      return status.value
    })()
    try {
      return await authCheckPromise
    } finally {
      checking.value = false
      authCheckPromise = null
    }
  }

  const ensureAuthenticated = async () => {
    if (status.value === 'authenticated') return true
    const result = await checkAuth()
    return result === 'authenticated'
  }

  /**
   * Terminates the backend session and clears local auth state. The caller
   * is responsible for navigating to `/login` afterwards. Local state is
   * always cleared, even if the backend call fails, so stale cookies can't
   * leave the UI in an "authenticated" state.
   */
  const logout = async (): Promise<boolean> => {
    const { logout: apiLogout } = useChatApi()
    let ok = false
    try {
      ok = await apiLogout()
    } finally {
      authCheckPromise = null
      status.value = 'unauthenticated'
    }
    return ok
  }

  return {
    status,
    checking,
    checkAuth,
    ensureAuthenticated,
    logout
  }
}
