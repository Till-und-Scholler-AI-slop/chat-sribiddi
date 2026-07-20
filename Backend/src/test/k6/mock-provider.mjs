import { createServer } from 'node:http'
import { performance } from 'node:perf_hooks'

const host = process.env.MOCK_PROVIDER_HOST || '0.0.0.0'
const port = positiveInteger(process.env.MOCK_PROVIDER_PORT, 18081)
const ttftMs = positiveInteger(process.env.MOCK_PROVIDER_TTFT_MS, 20)
const normalDurationMs = positiveInteger(process.env.MOCK_PROVIDER_STREAM_DURATION_MS, 60)
const backendMetricsUrl = process.env.BACKEND_METRICS_URL || 'http://backend:8080/internal/metrics'
const backendMetricsToken = process.env.BACKEND_METRICS_TOKEN || 'load-test-metrics'

const aborts = new Map()
const stats = {
  requests: 0,
  requestErrors: 0,
  normalStreamsStarted: 0,
  normalStreamsCompleted: 0,
  abortedStreams: 0,
  activeProviderStreams: 0,
  peakProviderStreams: 0,
  warmedBackendRssBytes: 0
}
let warmCapture

const server = createServer(async (request, response) => {
  const url = new URL(request.url || '/', `http://${host}:${port}`)
  try {
    if (request.method === 'GET' && url.pathname === '/health') {
      return json(response, 200, { status: 'UP' })
    }
    if (request.method === 'GET' && url.pathname === '/v1/models') {
      return json(response, 200, {
        data: [
          {
            id: 'mock/deterministic',
            name: 'Deterministic mock model',
            description: 'Local load-test provider',
            context_length: 32_768,
            pricing: { prompt: '0', completion: '0' }
          }
        ]
      })
    }
    if (request.method === 'GET' && url.pathname === '/__test/stats') {
      return json(response, 200, await statsSnapshot())
    }
    const abortMatch = /^\/__test\/aborts\/([^/]+)$/.exec(url.pathname)
    if (request.method === 'GET' && abortMatch) {
      const value = aborts.get(decodeURIComponent(abortMatch[1]))
      return value ? json(response, 200, value) : json(response, 404, { released: false })
    }
    if (request.method !== 'POST' || url.pathname !== '/v1/chat/completions') {
      return json(response, 404, { error: { message: 'Not found' } })
    }
    if (!String(request.headers.authorization || '').startsWith('Bearer ')) {
      return json(response, 401, { error: { message: 'Bearer credential required' } })
    }

    stats.requests += 1
    const payload = await readJson(request)
    const prompt = collectText(payload.messages)
    return streamCompletion(request, response, payload.model || 'mock/deterministic', prompt)
  } catch (error) {
    stats.requestErrors += 1
    if (response.headersSent) {
      response.destroy(error instanceof Error ? error : undefined)
      return
    }
    json(response, error?.statusCode || 500, {
      error: { message: error instanceof Error ? error.message : 'Mock provider error' }
    })
  }
})

server.keepAliveTimeout = 65_000
server.headersTimeout = 66_000
server.requestTimeout = 32 * 60_000

server.listen(port, host, () => {
  process.stdout.write(
    `${JSON.stringify({ level: 'info', event: 'mock_provider_started', host, port })}\n`
  )
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0))
    server.closeIdleConnections?.()
    setTimeout(() => process.exit(1), 2_000).unref()
  })
}

function streamCompletion(request, response, model, prompt) {
  const recoveryProbe = prompt.includes('K6_RECOVERY_PROBE')
  const heartbeatProbe = prompt.includes('K6_HEARTBEAT_PROBE')
  const abortProbe =
    recoveryProbe || heartbeatProbe
      ? null
      : /K6_ABORT_PROBE\s+id=([^\s]+)\s+abortAt=(\d+)/.exec(prompt)
  const normal = !abortProbe && !heartbeatProbe && !recoveryProbe
  const durationMs = abortProbe ? 5_000 : heartbeatProbe ? 15_500 : normalDurationMs
  const startedAt = performance.now()
  let completed = false
  let firstEventTimer
  let completionTimer

  stats.activeProviderStreams += 1
  stats.peakProviderStreams = Math.max(
    stats.peakProviderStreams,
    stats.activeProviderStreams
  )
  if (normal) stats.normalStreamsStarted += 1

  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive'
  })

  firstEventTimer = setTimeout(() => {
    if (response.destroyed || response.writableEnded) return
    writeChunk(response, {
      id: 'chatcmpl-load-test',
      object: 'chat.completion.chunk',
      created: 0,
      model,
      choices: [{ index: 0, delta: { content: 'deterministic mock delta' }, finish_reason: null }]
    })
  }, ttftMs)

  completionTimer = setTimeout(() => {
    if (response.destroyed || response.writableEnded) return
    writeChunk(response, {
      id: 'chatcmpl-load-test',
      object: 'chat.completion.chunk',
      created: 0,
      model,
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      usage: { prompt_tokens: 8, completion_tokens: 4, total_tokens: 12 }
    })
    response.end('data: [DONE]\n\n')
  }, durationMs)

  request.once('aborted', () => finish('aborted'))
  response.once('finish', () => finish('complete'))
  response.once('close', () => finish(response.writableEnded ? 'complete' : 'aborted'))

  function finish(reason) {
    if (completed) return
    completed = true
    clearTimeout(firstEventTimer)
    clearTimeout(completionTimer)
    stats.activeProviderStreams = Math.max(0, stats.activeProviderStreams - 1)
    if (reason === 'complete') {
      if (normal) {
        stats.normalStreamsCompleted += 1
        if (stats.normalStreamsCompleted === 100) captureWarmBackendRss()
      }
      return
    }
    stats.abortedStreams += 1
    if (abortProbe) {
      const abortAt = Number(abortProbe[2])
      aborts.set(decodeURIComponent(abortProbe[1]), {
        released: true,
        releaseLatencyMs: Math.max(0, Date.now() - abortAt),
        providerStreamLifetimeMs: Math.max(0, performance.now() - startedAt)
      })
    }
  }
}

function writeChunk(response, payload) {
  response.write(`data: ${JSON.stringify(payload)}\n\n`)
}

function collectText(messages) {
  if (!Array.isArray(messages)) return ''
  return messages
    .flatMap(message => {
      if (typeof message?.content === 'string') return [message.content]
      if (!Array.isArray(message?.content)) return []
      return message.content
        .map(part => (typeof part?.text === 'string' ? part.text : ''))
        .filter(Boolean)
    })
    .join('\n')
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let bytes = 0
    request.on('data', chunk => {
      bytes += chunk.length
      if (bytes > 1024 * 1024) {
        reject(Object.assign(new Error('Request body too large'), { statusCode: 413 }))
        request.destroy()
        return
      }
      chunks.push(chunk)
    })
    request.once('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'))
      } catch {
        reject(Object.assign(new Error('Invalid JSON'), { statusCode: 400 }))
      }
    })
    request.once('error', reject)
  })
}

function captureWarmBackendRss() {
  warmCapture ??= (async () => {
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const metrics = await backendMetrics().catch(() => null)
      if (metrics?.rssBytes > 0) {
        stats.warmedBackendRssBytes = metrics.rssBytes
        return
      }
      await wait(100)
    }
  })()
  return warmCapture
}

async function statsSnapshot() {
  if (stats.normalStreamsCompleted >= 100 && stats.warmedBackendRssBytes === 0) {
    await captureWarmBackendRss()
  }
  const backend = await backendMetrics().catch(() => ({
    rssBytes: 0,
    activeStreams: -1,
    activeJobs: -1
  }))
  const rssGrowthPercent =
    stats.warmedBackendRssBytes > 0 && backend.rssBytes > 0
      ? Math.max(
          0,
          ((backend.rssBytes - stats.warmedBackendRssBytes) /
            stats.warmedBackendRssBytes) *
            100
        )
      : null
  return {
    ...stats,
    backendRssBytes: backend.rssBytes,
    activeStreams: backend.activeStreams,
    activeJobs: backend.activeJobs,
    rssGrowthPercent,
    abortProbes: aborts.size
  }
}

async function backendMetrics() {
  const response = await fetch(backendMetricsUrl, {
    headers: { authorization: `Bearer ${backendMetricsToken}` },
    signal: AbortSignal.timeout(2_000)
  })
  if (!response.ok) throw new Error(`Backend metrics returned HTTP ${response.status}`)
  const body = await response.text()
  return {
    rssBytes: metricValue(body, 'chat_sribiddi_process_resident_memory_bytes'),
    activeStreams: metricValue(body, 'chat_active_streams'),
    activeJobs: metricSum(body, 'chat_document_jobs')
  }
}

function metricValue(body, name) {
  const line = body
    .split(/\r?\n/)
    .find(value => value.startsWith(`${name} `))
  return line ? Number(line.slice(name.length + 1)) : 0
}

function metricSum(body, name) {
  return body
    .split(/\r?\n/)
    .filter(line => line.startsWith(`${name}{`) || line.startsWith(`${name} `))
    .reduce((sum, line) => sum + Number(line.trim().split(/\s+/).at(-1) || 0), 0)
}

function json(response, status, payload) {
  if (response.destroyed || response.writableEnded) return
  const body = JSON.stringify(payload)
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  })
  response.end(body)
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function positiveInteger(value, fallback) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}
