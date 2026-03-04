import { DEFAULT_BACKEND_BASE_URL, normalizeBaseUrl } from '~/composables/useChatApi'

type AuthState = 'unknown' | 'authenticated' | 'unauthenticated'

export function useAuth() {
  const { settings } = useUserSettings()
  const status = useState<AuthState>('auth-status', () => 'unknown')
  const checking = useState<boolean>('auth-checking', () => false)

  const checkAuth = async () => {
    if (checking.value) return status.value
    checking.value = true
    try {
      const base = normalizeBaseUrl(settings.value.backendBaseUrl.trim()) || DEFAULT_BACKEND_BASE_URL
      const response = await fetch(`${base}/secured`, {
        credentials: 'include'
      })
      status.value = response.ok ? 'authenticated' : 'unauthenticated'
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

  return {
    status,
    checking,
    checkAuth,
    ensureAuthenticated
  }
}
