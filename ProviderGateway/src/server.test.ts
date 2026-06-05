import { createServer, type Server } from 'node:http'
import assert from 'node:assert/strict'
import test from 'node:test'
import { once } from 'node:events'
import {
  createProviderGatewayServer,
  validateOpenAiCompatibleBaseUrl
} from './server.js'

test('validateOpenAiCompatibleBaseUrl rejects private endpoints unless enabled', () => {
  assert.throws(
    () => validateOpenAiCompatibleBaseUrl('http://127.0.0.1:1234/v1', false),
    /Private or local provider endpoints are disabled/
  )
  assert.equal(
    validateOpenAiCompatibleBaseUrl('http://127.0.0.1:1234/v1', true),
    'http://127.0.0.1:1234/v1'
  )
})

test('gateway streams mock provider responses', async () => {
  const gateway = await listen(createProviderGatewayServer())
  try {
    const response = await fetch(`${gateway.url}/v1/chat/stream`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        providerId: 'mock',
        modelId: 'mock-model',
        messages: [{ role: 'user', content: 'hello' }]
      })
    })

    assert.equal(response.status, 200)
    const body = await response.text()
    assert.match(body, /event: message/)
    assert.match(body, /mock:mock-model/)
    assert.match(body, /event: done/)
  } finally {
    await close(gateway.server)
  }
})

test('gateway streams OpenAI-compatible provider responses', async () => {
  const provider = await listen(fakeOpenAiCompatibleProvider())
  const gateway = await listen(createProviderGatewayServer())
  try {
    const response = await fetch(`${gateway.url}/v1/chat/stream`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        providerId: 'openai-compatible',
        modelId: 'test-model',
        baseUrl: `${provider.url}/v1`,
        apiKey: 'sk-user-secret',
        messages: [{ role: 'user', content: 'hello' }],
        options: { temperature: 0.2, maxOutputTokens: 64 }
      })
    })

    assert.equal(response.status, 200)
    const body = await response.text()
    assert.match(body, /event: message/)
    assert.match(body, /hello/)
    assert.match(body, /world/)
    assert.match(body, /event: done/)
  } finally {
    await close(gateway.server)
    await close(provider.server)
  }
})

function fakeOpenAiCompatibleProvider() {
  return createServer(async (request, response) => {
    assert.equal(request.method, 'POST')
    assert.equal(request.url, '/v1/chat/completions')
    await drain(request)

    response.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache'
    })
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}\n\n'
    )
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}\n\n'
    )
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}\n\n'
    )
    response.write('data: [DONE]\n\n')
    response.end()
  })
}

async function drain(request: NodeJS.ReadableStream) {
  for await (const _ of request) {
    // drain request body
  }
}

async function listen(server: Server) {
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (!address || typeof address === 'string') {
    throw new Error('Expected TCP server address')
  }
  return {
    server,
    url: `http://127.0.0.1:${address.port}`
  }
}

async function close(server: Server) {
  server.close()
  await once(server, 'close')
}
