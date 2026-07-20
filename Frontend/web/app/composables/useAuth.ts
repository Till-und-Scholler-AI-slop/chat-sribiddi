import { AuthCheckError } from '~/composables/useChatApi'

type AuthState = 'unknown' | 'authenticated' | 'unauthenticated'

type AuthCoordinator = {
  promise: Promise<AuthState> | null
  generation: number
}

const coordinators = new WeakMap<object, AuthCoordinator>()

function coordinatorFor(app: object) {
  let coordinator = coordinators.get(app)
  if (!coordinator) {
    coordinator = { promise: null, generation: 0 }
    coordinators.set(app, coordinator)
  }
  return coordinator
}

export function useAuth() {
  const coordinator = coordinatorFor(useNuxtApp())
  const status = useState<AuthState>('auth-status', () => 'unknown')
  const checking = useState<boolean>('auth-checking', () => false)

  const checkAuth = async () => {
    if (coordinator.promise) return coordinator.promise
    checking.value = true
    const generation = ++coordinator.generation
    const promise = (async () => {
      const { checkSession } = useChatApi()
      try {
        const ok = await checkSession()
        if (generation === coordinator.generation) {
          status.value = ok ? 'authenticated' : 'unauthenticated'
        }
      } catch (err) {
        if (generation === coordinator.generation) {
          if (err instanceof AuthCheckError && err.code === 'network') {
            status.value = 'unknown'
          } else {
            status.value = 'unauthenticated'
          }
        }
      }
      return status.value
    })()
    coordinator.promise = promise
    try {
      return await promise
    } finally {
      if (coordinator.promise === promise) {
        checking.value = false
        coordinator.promise = null
      }
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
      coordinator.generation++
      coordinator.promise = null
      checking.value = false
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
