import type { Page, Route } from '@playwright/test'

type MockBackendOptions = {
  authenticated?: boolean
}

const USER_SETTINGS_STORAGE_KEY = 'chat-sribiddi:user-settings:v2'
const E2E_BACKEND_BASE_URL = 'http://127.0.0.1:8080'

const modelPayload = (provider: string) => [
  {
    id: `${provider.toLowerCase()}-mock`,
    label: `${provider} Mock`,
    provider,
    availability: 'ALWAYS',
    promptPrice: '0',
    completionPrice: '0'
  }
]

const markdownResponseChunks = [
  '## Markstream check\n\n',
  '- Alpha item\n',
  '- Beta item\n\n',
  '```ts\n',
  'const ok = true\n',
  '```'
]

const streamTextEvent = (content: string) =>
  `event: text\ndata: ${JSON.stringify({ type: 'text', content })}\n\n`

function isBackendRequest(url: URL) {
  if ((url.hostname === 'localhost' || url.hostname === '127.0.0.1') && url.port === '8080') {
    return true
  }
  return url.pathname === '/api' || url.pathname.startsWith('/api/')
}

function corsHeaders(route: Route) {
  const origin = route.request().headers().origin || '*'
  return {
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Headers': 'Content-Type, X-Chat-User-Id, X-XSRF-TOKEN',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
    'Access-Control-Allow-Origin': origin,
    Vary: 'Origin'
  }
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    headers: {
      ...corsHeaders(route),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body)
  })
}

export async function mockBackend(page: Page, options: MockBackendOptions = {}) {
  let authenticated = options.authenticated ?? true
  const chats = new Map<string, { chatId: string; title: string }>()

  await page.addInitScript(({ backendBaseUrl, storageKey }) => {
    const fallbackSettings = {
      backendBaseUrl,
      favoriteModels: [],
      lastUsedModel: null,
      showPreSendCostEstimate: true,
      expertMode: false,
      guestImportDismissed: false
    }

    try {
      const existing = window.localStorage.getItem(storageKey)
      const parsed = existing ? JSON.parse(existing) as Record<string, unknown> : {}
      window.localStorage.setItem(
        storageKey,
        JSON.stringify({
          ...fallbackSettings,
          ...parsed,
          backendBaseUrl
        })
      )
    } catch {
      window.localStorage.setItem(storageKey, JSON.stringify(fallbackSettings))
    }
  }, { backendBaseUrl: E2E_BACKEND_BASE_URL, storageKey: USER_SETTINGS_STORAGE_KEY })

  const handleRoute = async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api(?=\/|$)/, '') || '/'

    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: corsHeaders(route) })
      return
    }

    if (path === '/secured') {
      await route.fulfill({ status: authenticated ? 204 : 401, headers: corsHeaders(route) })
      return
    }

    if (path === '/logout') {
      authenticated = false
      await route.fulfill({ status: 204, headers: corsHeaders(route) })
      return
    }

    if (path === '/account/settings' || path === '/settings') {
      await fulfillJson(route, {
        customInstructions: '',
        billingModePreference: 'AUTO',
        favoriteModels: [],
        lastUsedModel: null
      })
      return
    }

    if (path === '/billing/me') {
      await fulfillJson(route, { plan: 'FREE', status: 'active' })
      return
    }

    if (path.startsWith('/models/')) {
      await fulfillJson(route, modelPayload(path.split('/').pop()?.toUpperCase() || 'OPENAI'))
      return
    }

    if (path === '/chats' && request.method() === 'GET') {
      await fulfillJson(route, [...chats.values()])
      return
    }

    if (path === '/chats' && request.method() === 'POST') {
      const chat = { chatId: 'chat-e2e', title: 'E2E Chat' }
      chats.set(chat.chatId, chat)
      await fulfillJson(route, chat)
      return
    }

    if (path === '/chats/chat-e2e/messages' && request.method() === 'GET') {
      await fulfillJson(route, [])
      return
    }

    if (path === '/chats/chat-e2e/messages/stream') {
      await route.fulfill({
        status: 200,
        headers: {
          ...corsHeaders(route),
          'Content-Type': 'text/event-stream'
        },
        body:
          markdownResponseChunks.map(streamTextEvent).join('') +
          'event: done\n' +
          'data: {"type":"done","messageId":123,"hasToolCalls":false,"promptTokens":4,"generationTokens":2,"totalTokens":6}\n\n'
      })
      return
    }

    if (path === '/anonymous/chats/stream') {
      await route.fulfill({
        status: 200,
        headers: {
          ...corsHeaders(route),
          'Content-Type': 'text/event-stream'
        },
        body:
          markdownResponseChunks.map(streamTextEvent).join('') +
          'event: done\n' +
          'data: {"type":"done","hasToolCalls":false,"promptTokens":4,"generationTokens":2,"totalTokens":6}\n\n'
      })
      return
    }

    if (path === '/chats/import' && request.method() === 'POST') {
      const chat = { chatId: 'chat-imported', title: 'Imported guest chat' }
      chats.set(chat.chatId, chat)
      await fulfillJson(route, [chat])
      return
    }

    await fulfillJson(route, {})
  }

  await page.context().route('**/*', async route => {
    const url = new URL(route.request().url())
    if (isBackendRequest(url)) {
      await handleRoute(route)
      return
    }
    await route.continue()
  })
}
