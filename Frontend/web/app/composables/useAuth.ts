type AuthState = 'unknown' | 'authenticated' | 'unauthenticated'

export function useAuth() {
  const status = useState<AuthState>('auth-status', () => 'unknown')
  const checking = useState<boolean>('auth-checking', () => false)

  const checkAuth = async () => {
    if (checking.value) return status.value
    checking.value = true
    try {
      const { checkSession } = useChatApi()
      const ok = await checkSession()
      status.value = ok ? 'authenticated' : 'unauthenticated'
    } catch {
      status.value = 'unauthenticated'
    } finally {
      checking.value = false
    }
    return status.value
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
