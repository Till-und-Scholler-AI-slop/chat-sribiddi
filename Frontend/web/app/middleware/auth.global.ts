export default defineNuxtRouteMiddleware(async to => {
  if (import.meta.server) return
  // Allow public pages without auth checks.
  const normalizedPath = to.path.replace(/\/+$/, '') || '/'
  if (
    ['/', '/chat', '/login', '/impressum', '/datenschutz', '/agb', '/pricing', '/models']
      .includes(normalizedPath)
  ) return

  const { ensureAuthenticated } = useAuth()
  const ok = await ensureAuthenticated()
  if (!ok) {
    return navigateTo('/login')
  }
})
