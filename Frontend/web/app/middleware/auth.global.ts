export default defineNuxtRouteMiddleware(async to => {
  if (import.meta.server) return
  // Allow the login page without checks
  if (to.path === '/login') return

  const { ensureAuthenticated } = useAuth()
  const ok = await ensureAuthenticated()
  if (!ok) {
    return navigateTo('/login')
  }
})
