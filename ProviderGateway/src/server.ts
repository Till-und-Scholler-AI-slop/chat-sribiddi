import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { pathToFileURL } from 'node:url'
import { createOpenAICompatible } from '@ai-sdk/openai-compatible'
import { streamText, type ModelMessage } from 'ai'

type GatewayMessage = {
  role: 'system' | 'user' | 'assistant'
  content: string
}

type GatewayRuntimeOptions = {
  temperature?: number
  topP?: number
  topK?: number
  maxOutputTokens?: number
  frequencyPenalty?: number
  presencePenalty?: number
  stopSequences?: string[]
}

export type GatewayChatRequest = {
  providerId: string
  modelId: string
  messages: GatewayMessage[]
  stream?: boolean
  authMethod?: string
  apiKey?: string | null
  baseUrl?: string | null
  headers?: Record<string, string>
  queryParams?: Record<string, string>
  options?: GatewayRuntimeOptions | null
}

type GatewayErrorPayload = {
  error: {
    type: string
    message: string
  }
}

const host = process.env.PROVIDER_GATEWAY_HOST || '127.0.0.1'
const port = Number(process.env.PROVIDER_GATEWAY_PORT || '8095')
const allowPrivateBaseUrls = process.env.PROVIDER_GATEWAY_ALLOW_PRIVATE_BASE_URLS === 'true'

export function sendJson(response: ServerResponse, status: number, payload: unknown) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  })
  response.end(JSON.stringify(payload))
}

async function readJson<T>(request: IncomingMessage): Promise<T> {
  const chunks: Buffer[] = []
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as T
}

function writeSseEvent(response: ServerResponse, event: string, payload: unknown) {
  response.write(`event: ${event}\n`)
  response.write(`data: ${JSON.stringify(payload)}\n\n`)
}

function startSse(response: ServerResponse) {
  response.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive'
  })
}

function sendMockStream(response: ServerResponse, request: GatewayChatRequest) {
  startSse(response)
  writeSseEvent(response, 'message', { type: 'text', content: `mock:${request.modelId}` })
  writeSseEvent(response, 'done', { type: 'done' })
  response.end()
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new GatewayRequestError('invalid_request', `${field} must not be blank`)
  }
  return value.trim()
}

function validateMessages(messages: unknown): GatewayMessage[] {
  if (!Array.isArray(messages) || messages.length === 0) {
    throw new GatewayRequestError('invalid_request', 'messages must contain at least one item')
  }
  return messages.map((message, index) => {
    if (!message || typeof message !== 'object') {
      throw new GatewayRequestError('invalid_request', `messages[${index}] must be an object`)
    }
    const candidate = message as Partial<GatewayMessage>
    if (!candidate.role || !['system', 'user', 'assistant'].includes(candidate.role)) {
      throw new GatewayRequestError('invalid_request', `messages[${index}].role is not supported`)
    }
    return {
      role: candidate.role,
      content: requireText(candidate.content, `messages[${index}].content`)
    }
  })
}

function validateHeaders(headers: unknown): Record<string, string> | undefined {
  if (headers == null) return undefined
  if (typeof headers !== 'object' || Array.isArray(headers)) {
    throw new GatewayRequestError('invalid_request', 'headers must be an object')
  }
  const values: Record<string, string> = {}
  for (const [key, value] of Object.entries(headers)) {
    if (typeof value === 'string' && value.trim().length > 0) {
      values[key] = value
    }
  }
  return values
}

function validateStringRecord(value: unknown, field: string): Record<string, string> | undefined {
  if (value == null) return undefined
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw new GatewayRequestError('invalid_request', `${field} must be an object`)
  }
  const values: Record<string, string> = {}
  for (const [key, item] of Object.entries(value)) {
    if (typeof item === 'string' && item.trim().length > 0) {
      values[key] = item
    }
  }
  return values
}

export function validateOpenAiCompatibleBaseUrl(
  rawBaseUrl: string | null | undefined,
  allowPrivate = allowPrivateBaseUrls
): string {
  const value = requireText(rawBaseUrl, 'baseUrl')
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    throw new GatewayRequestError('invalid_request', 'baseUrl must be a valid URL')
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new GatewayRequestError('invalid_request', 'baseUrl must use http or https')
  }
  if (parsed.username || parsed.password) {
    throw new GatewayRequestError('invalid_request', 'baseUrl must not include credentials')
  }
  if (parsed.search || parsed.hash) {
    throw new GatewayRequestError('invalid_request', 'baseUrl must not include query or fragment')
  }
  if (!allowPrivate && isPrivateHost(parsed.hostname)) {
    throw new GatewayRequestError(
      'local_endpoint_disabled',
      'Private or local provider endpoints are disabled for this gateway'
    )
  }
  return parsed.toString().replace(/\/$/, '')
}

function isPrivateHost(hostname: string): boolean {
  const host = hostname.toLowerCase()
  if (host === 'localhost' || host.endsWith('.localhost')) return true
  if (host === '::1' || host === '[::1]') return true
  if (host.startsWith('127.')) return true
  if (host.startsWith('10.')) return true
  if (host.startsWith('192.168.')) return true
  if (host.startsWith('169.254.')) return true
  const parts = host.split('.').map(Number)
  if (parts.length === 4 && parts.every(Number.isInteger)) {
    if (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return true
  }
  return false
}

function normalizeChatRequest(body: GatewayChatRequest): GatewayChatRequest {
  const providerId = requireText(body.providerId, 'providerId')
  const modelId = requireText(body.modelId, 'modelId')
  return {
    ...body,
    providerId,
    modelId,
    messages: validateMessages(body.messages),
    headers: validateHeaders(body.headers),
    queryParams: validateStringRecord(body.queryParams, 'queryParams')
  }
}

function runtimeOptions(options: GatewayRuntimeOptions | null | undefined): GatewayRuntimeOptions {
  if (!options) return {}
  return {
    temperature: numberOrUndefined(options.temperature),
    topP: numberOrUndefined(options.topP),
    topK: numberOrUndefined(options.topK),
    maxOutputTokens: numberOrUndefined(options.maxOutputTokens),
    frequencyPenalty: numberOrUndefined(options.frequencyPenalty),
    presencePenalty: numberOrUndefined(options.presencePenalty),
    stopSequences: Array.isArray(options.stopSequences)
      ? options.stopSequences.filter(item => typeof item === 'string' && item.length > 0)
      : undefined
  }
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

export async function streamOpenAiCompatible(response: ServerResponse, request: GatewayChatRequest) {
  const baseURL = validateOpenAiCompatibleBaseUrl(request.baseUrl)
  const provider = createOpenAICompatible({
    name: request.providerId,
    baseURL,
    apiKey: request.apiKey?.trim() || undefined,
    headers: request.headers,
    queryParams: request.queryParams,
    includeUsage: true
  })

  startSse(response)
  const result = streamText({
    model: provider.chatModel(request.modelId),
    messages: request.messages as ModelMessage[],
    ...runtimeOptions(request.options)
  })

  for await (const chunk of result.textStream) {
    writeSseEvent(response, 'message', { type: 'text', content: chunk })
  }
  const usage = await safeUsage(result.usage)
  writeSseEvent(response, 'done', {
    type: 'done',
    promptTokens: usage?.inputTokens,
    generationTokens: usage?.outputTokens,
    totalTokens: usage?.totalTokens
  })
  response.end()
}

async function safeUsage(
  usage: PromiseLike<{ inputTokens?: number; outputTokens?: number; totalTokens?: number }>
) {
  try {
    return await usage
  } catch {
    return undefined
  }
}

function canUseOpenAiCompatible(request: GatewayChatRequest): boolean {
  return Boolean(request.baseUrl && request.baseUrl.trim())
}

export function createProviderGatewayServer() {
  return createServer(async (request, response) => {
    try {
      if (request.method === 'GET' && request.url === '/health') {
        sendJson(response, 200, { status: 'ok', service: 'provider-gateway' })
        return
      }

      if (request.method === 'POST' && request.url === '/v1/chat/stream') {
        const body = normalizeChatRequest(await readJson<GatewayChatRequest>(request))
        if (body.providerId === 'mock') {
          sendMockStream(response, body)
          return
        }
        if (canUseOpenAiCompatible(body)) {
          await streamOpenAiCompatible(response, body)
          return
        }
        sendJson(response, 501, {
          error: {
            type: 'provider_not_enabled',
            message:
              'This provider requires an enabled gateway adapter or an OpenAI-compatible baseUrl.'
          }
        })
        return
      }

      sendJson(response, 404, { error: { type: 'not_found', message: 'Not found' } })
    } catch (error) {
      sendGatewayError(response, error)
    }
  })
}

function sendGatewayError(response: ServerResponse, error: unknown) {
  const payload = errorPayload(error)
  if (response.headersSent) {
    writeSseEvent(response, 'error', {
      type: 'error',
      errorType: payload.error.type,
      content: payload.error.message
    })
    response.end()
    return
  }
  const status = error instanceof GatewayRequestError ? error.status : 400
  sendJson(response, status, payload)
}

function errorPayload(error: unknown): GatewayErrorPayload {
  if (error instanceof GatewayRequestError) {
    return { error: { type: error.type, message: error.message } }
  }
  return {
    error: {
      type: 'provider_error',
      message: error instanceof Error ? sanitizeErrorMessage(error.message) : 'Provider request failed'
    }
  }
}

function sanitizeErrorMessage(message: string): string {
  return message.replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [redacted]')
}

class GatewayRequestError extends Error {
  constructor(
    readonly type: string,
    message: string,
    readonly status = 400
  ) {
    super(message)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createProviderGatewayServer().listen(port, host, () => {
    console.log(`provider-gateway listening on http://${host}:${port}`)
  })
}
