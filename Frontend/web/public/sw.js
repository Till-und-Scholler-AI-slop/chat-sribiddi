const CACHE_NAME = 'chat-sribiddi-v2'
const APP_SHELL = [
  '/',
  '/chat',
  '/manifest.webmanifest',
  '/favicon.ico',
  '/icon.svg'
]
const NETWORK_ONLY_PREFIXES = ['/api/', '/login/oauth2/', '/oauth2/']

self.addEventListener('install', event => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then(cache => Promise.allSettled(APP_SHELL.map(path => cache.add(path))))
      .catch(() => undefined)
  )
  self.skipWaiting()
})

self.addEventListener('activate', event => {
  event.waitUntil(
    caches
      .keys()
      .then(keys =>
        Promise.all(keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key)))
      )
  )
  self.clients.claim()
})

self.addEventListener('fetch', event => {
  const request = event.request
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  if (NETWORK_ONLY_PREFIXES.some(prefix => url.pathname.startsWith(prefix))) return

  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, '/chat'))
    return
  }

  event.respondWith(staleWhileRevalidate(request))
})

async function networkFirst(request, fallbackPath) {
  const cache = await caches.open(CACHE_NAME)
  try {
    const response = await fetch(request)
    if (response.ok) {
      await cache.put(request, response.clone()).catch(() => undefined)
    }
    return response
  } catch {
    return (await cache.match(request)) || (await cache.match(fallbackPath)) || new Response('', { status: 503 })
  }
}

async function staleWhileRevalidate(request) {
  const cache = await caches.open(CACHE_NAME)
  const cached = await cache.match(request)
  const network = fetch(request)
    .then(response => {
      if (response.ok) {
        cache.put(request, response.clone()).catch(() => undefined)
      }
      return response
    })
    .catch(() => cached || new Response('', { status: 503 }))

  return cached || network
}
