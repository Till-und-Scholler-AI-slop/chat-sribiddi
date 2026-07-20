self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys()
    await Promise.allSettled(
      keys
        .filter(key => key.startsWith('chat-sribiddi-'))
        .map(key => caches.delete(key))
    )
    await self.registration.unregister()
    await self.clients.claim()
  })())
})
