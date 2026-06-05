import http from 'k6/http'
import { check, sleep } from 'k6'
import { byokLoadOptions } from './k6.config.js'

export const options = byokLoadOptions

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const USER_PREFIX = __ENV.K6_USER_PREFIX || 'k6-byok-user'
const MODEL_ID = __ENV.K6_MODEL_ID || 'openrouter/free'
const PROVIDER = __ENV.K6_PROVIDER || 'OPENROUTER'
const CHAT_TITLE = __ENV.K6_CHAT_TITLE || 'k6 BYOK load test'
const PROMPT = __ENV.K6_PROMPT || 'Reply with a short health-check sentence.'
const AUTH_COOKIE = __ENV.K6_AUTH_COOKIE || ''

export default function () {
  const userId = `${USER_PREFIX}-${__VU}`
  const headers = {
    'Content-Type': 'application/json',
    'X-Chat-User-Id': userId
  }
  if (AUTH_COOKIE) {
    headers.Cookie = AUTH_COOKIE
  }

  const createChat = http.post(
    `${BASE_URL}/chats`,
    JSON.stringify({ title: CHAT_TITLE }),
    { headers, tags: { endpoint: 'create_chat' } }
  )
  check(createChat, {
    'chat created': response => response.status === 201,
    'chat id present': response => Boolean(response.json('chatId'))
  })

  const chatId = createChat.json('chatId')
  if (!chatId) {
    return
  }

  const sendMessage = http.post(
    `${BASE_URL}/chats/${chatId}/messages`,
    JSON.stringify({
      content: `${PROMPT} vu=${__VU} iter=${__ITER}`,
      provider: PROVIDER,
      modelId: MODEL_ID,
      webSearchEnabled: false
    }),
    { headers, tags: { endpoint: 'send_message' } }
  )
  check(sendMessage, {
    'message accepted': response => response.status === 200,
    'assistant message returned': response => Boolean(response.json('message'))
  })

  sleep(Number(__ENV.K6_SLEEP_SECONDS || 0.2))
}
