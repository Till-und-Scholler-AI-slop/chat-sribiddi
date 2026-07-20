import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import { byokLoadOptions } from './k6.config.js'

export const options = byokLoadOptions

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '')
const PROVIDER_STATS_URL = (__ENV.PROVIDER_STATS_URL || 'http://127.0.0.1:18081').replace(
  /\/$/,
  ''
)
const USER_PREFIX = __ENV.K6_USER_PREFIX || 'k6-session'
const MODEL_ID = __ENV.K6_MODEL_ID || 'mock/deterministic'
const PROVIDER = __ENV.K6_PROVIDER || 'OPENROUTER'
const TEST_MODE = (__ENV.TEST_MODE || 'baseline').toLowerCase()
const EXPECTED_BASELINE_STREAMS =
  positiveInteger(__ENV.K6_VUS, 100) * positiveInteger(__ENV.K6_ITERATIONS_PER_VU, 10)

const normalPathErrors = new Rate('normal_path_errors')
const nonProviderApiDuration = new Trend('non_provider_api_duration_ms', true)
const mockSseFirstEvent = new Trend('mock_sse_first_event_ms', true)
const normalSseStreams = new Counter('normal_sse_streams')

let chatId

export function setup() {
  const live = http.get(`${BASE_URL}/health/live`, {
    tags: { endpoint: 'test_setup', normal_path: 'false' }
  })
  const ready = http.get(`${BASE_URL}/health/ready`, {
    tags: { endpoint: 'test_setup', normal_path: 'false' }
  })
  check({ live, ready }, {
    'real backend, PostgreSQL and gateway are ready': responses =>
      responses.live.status === 200 &&
      responses.ready.status === 200 &&
      safeJson(responses.ready)?.components?.database === 'UP' &&
      safeJson(responses.ready)?.components?.providerGateway === 'UP'
  })
  return { expectedBaselineStreams: EXPECTED_BASELINE_STREAMS }
}

export function streamSession() {
  const headers = sessionHeaders()
  if (!chatId) {
    const key = saveProviderKey(headers, true)
    const created = createChat(headers, `k6 deterministic session ${__VU}`, true)
    if (!key || !created) return
    chatId = created
  }

  const capabilities = http.get(`${BASE_URL}/chat/capabilities`, {
    headers,
    tags: { endpoint: 'non_provider_api', normal_path: 'true' }
  })
  nonProviderApiDuration.add(capabilities.timings.duration)
  const capabilitiesOk =
    capabilities.status === 200 && Number(jsonValue(capabilities, 'maxAttachments')) > 0
  normalPathErrors.add(!capabilitiesOk)
  check(capabilities, {
    'capability API succeeds': () => capabilitiesOk
  })

  const response = streamChat(
    chatId,
    headers,
    `Deterministic load probe vu=${__VU} iter=${__ITER}`,
    '10s',
    { endpoint: 'chat_stream', normal_path: 'true' }
  )

  if (response.status === 200) mockSseFirstEvent.add(response.timings.waiting)
  const body = response.body || ''
  const streamOk =
    response.status === 200 &&
    body.includes('event: message') &&
    body.includes('event: done') &&
    body.includes('"messageStatus":"COMPLETE"')
  normalPathErrors.add(!streamOk)
  if (streamOk) normalSseStreams.add(1)
  check(response, {
    'real backend SSE stream is complete and typed': () => streamOk,
    'first mock-provider SSE timing is captured': current => current.timings.waiting > 0
  })

  sleep(TEST_MODE === 'soak' ? Number(__ENV.SOAK_PACE_SECONDS || 1) : 0.01)
}

export function abortProbe() {
  const userId = `${USER_PREFIX}-abort-${__VU}`
  const headers = sessionHeaders(userId)
  if (!saveProviderKey(headers, false)) return
  const probeChatId = createChat(headers, 'k6 abort propagation probe', false)
  if (!probeChatId) return

  const probeId = `abort-${Date.now()}-${__VU}`
  const abortAt = Date.now() + 200
  const response = streamChat(
    probeChatId,
    headers,
    `K6_ABORT_PROBE id=${encodeURIComponent(probeId)} abortAt=${abortAt}`,
    '200ms',
    { endpoint: 'expected_abort', normal_path: 'false' }
  )
  check(response, {
    'client-side timeout interrupts the real backend stream': current =>
      current.status === 0 || Boolean(current.error) || Boolean(current.error_code)
  })

  let release
  for (let attempt = 0; attempt < 20 && !release; attempt += 1) {
    sleep(0.05)
    const result = http.get(
      `${PROVIDER_STATS_URL}/__test/aborts/${encodeURIComponent(probeId)}`,
      { tags: { endpoint: 'abort_observation', normal_path: 'false' } }
    )
    if (result.status === 200) release = safeJson(result)
  }

  let restarted = false
  for (let attempt = 0; attempt < 20 && !restarted; attempt += 1) {
    const retry = streamChat(
      probeChatId,
      headers,
      'K6_RECOVERY_PROBE verify stream slot release',
      '2s',
      { endpoint: 'slot_recovery', normal_path: 'false' }
    )
    restarted = retry.status === 200 && (retry.body || '').includes('event: done')
    if (!restarted) sleep(0.05)
  }

  check({ release, restarted }, {
    'abort reaches the mock provider within one second': value =>
      value.release?.released === true && value.release.releaseLatencyMs <= 1000,
    'aborted backend stream slot is reusable within one second': value => value.restarted === true
  })
}

export function heartbeatProbe() {
  const headers = sessionHeaders(`${USER_PREFIX}-heartbeat-${__VU}`)
  if (!saveProviderKey(headers, false)) return
  const probeChatId = createChat(headers, 'k6 heartbeat probe', false)
  if (!probeChatId) return
  const response = streamChat(
    probeChatId,
    headers,
    'K6_HEARTBEAT_PROBE keep provider stream open',
    '20s',
    { endpoint: 'heartbeat_probe', normal_path: 'false' }
  )
  check(response, {
    '15-second heartbeat is emitted by the real backend': current =>
      current.status === 200 && (current.body || '').includes('event: heartbeat')
  })
}

export function teardown(data) {
  let stats
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = http.get(`${PROVIDER_STATS_URL}/__test/stats`, {
      tags: { endpoint: 'test_teardown', normal_path: 'false' }
    })
    stats = safeJson(response)
    if (
      response.status === 200 &&
      stats?.activeStreams === 0 &&
      stats?.activeProviderStreams === 0 &&
      stats?.activeJobs === 0
    ) {
      break
    }
    sleep(0.1)
  }
  check(stats, {
    'backend, provider and document jobs are drained after the run': value =>
      value?.activeStreams === 0 &&
      value?.activeProviderStreams === 0 &&
      value?.activeJobs === 0,
    ...(TEST_MODE === 'soak'
      ? {
          'backend soak RSS stays within 20 percent of its warmed baseline': value =>
            value?.warmedBackendRssBytes > 0 && value?.rssGrowthPercent <= 20
        }
      : {
          'all configured normal provider streams completed': value =>
            value?.normalStreamsCompleted === data.expectedBaselineStreams
        })
  })
}

export default streamSession

function saveProviderKey(headers, normalPath) {
  const response = http.put(
    `${BASE_URL}/apikeys/${PROVIDER}`,
    JSON.stringify({ apiKey: 'load-test-provider-key' }),
    { headers, tags: { endpoint: 'save_provider_key', normal_path: String(normalPath) } }
  )
  if (normalPath) {
    nonProviderApiDuration.add(response.timings.duration)
    normalPathErrors.add(response.status !== 204)
  }
  check(response, { 'load-test BYOK credential is stored': current => current.status === 204 })
  return response.status === 204
}

function createChat(headers, title, normalPath) {
  const response = http.post(`${BASE_URL}/chats`, JSON.stringify({ title }), {
    headers,
    tags: { endpoint: 'create_chat', normal_path: String(normalPath) }
  })
  if (normalPath) {
    nonProviderApiDuration.add(response.timings.duration)
    normalPathErrors.add(response.status !== 201)
  }
  const createdId = jsonValue(response, 'chatId')
  const created = response.status === 201 && typeof createdId === 'string'
  check(response, { 'chat is persisted by the real backend': () => created })
  return created ? createdId : null
}

function streamChat(chat, headers, content, timeout, tags) {
  return http.post(
    `${BASE_URL}/chats/${encodeURIComponent(chat)}/messages/stream`,
    JSON.stringify({
      content,
      provider: PROVIDER,
      modelId: MODEL_ID,
      webSearchEnabled: false
    }),
    { headers, timeout, tags }
  )
}

function sessionHeaders(userId = `${USER_PREFIX}-${__VU}`) {
  const headers = {
    'Content-Type': 'application/json',
    'X-Chat-User-Id': userId
  }
  if (__ENV.K6_AUTH_COOKIE) headers.Cookie = __ENV.K6_AUTH_COOKIE
  return headers
}

function jsonValue(response, key) {
  return safeJson(response)?.[key]
}

function safeJson(response) {
  try {
    return response.json()
  } catch {
    return undefined
  }
}

function positiveInteger(value, fallback) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}
