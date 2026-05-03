import type { Page, Route } from '@playwright/test'

type MockBackendOptions = {
  authenticated?: boolean
}

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

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body)
  })
}

export async function mockBackend(page: Page, options: MockBackendOptions = {}) {
  let authenticated = options.authenticated ?? true
  const chats = new Map<string, { chatId: string; title: string }>()

  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (url.hostname === '127.0.0.1' && url.port === '3000') {
      await route.continue()
      return
    }

    if (path === '/secured') {
      await route.fulfill({ status: authenticated ? 204 : 401 })
      return
    }

    if (path === '/logout') {
      authenticated = false
      await route.fulfill({ status: 204 })
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
        headers: { 'Content-Type': 'text/event-stream' },
        body:
          'event: text\n' +
          'data: {"type":"text","content":"Mocked response"}\n\n' +
          'event: done\n' +
          'data: {"type":"done","messageId":123,"hasToolCalls":false,"promptTokens":4,"generationTokens":2,"totalTokens":6}\n\n'
      })
      return
    }

    await fulfillJson(route, {})
  })
}
