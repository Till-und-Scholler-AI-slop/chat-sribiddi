export default defineNuxtPlugin(() => {
  const disableLegacyOfflineCache = async () => {
    if ('serviceWorker' in navigator) {
      const registrations = await navigator.serviceWorker.getRegistrations().catch(() => [])
      await Promise.allSettled(registrations.map(registration => registration.unregister()))
    }
    if ('caches' in globalThis) {
      const keys = await caches.keys().catch(() => [])
      await Promise.allSettled(
        keys
          .filter(key => key.startsWith('chat-sribiddi-'))
          .map(key => caches.delete(key))
      )
    }
  }

  void disableLegacyOfflineCache()
})
