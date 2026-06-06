import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  completeDeviceConnectionRequest,
  startConnectionRequest,
  useKeyApi
} from '../useKeyApi'

const apiMock = vi.hoisted(() => ({
  requestJson: vi.fn()
}))

vi.mock('../useChatApi', () => ({
  useChatApi: () => apiMock
}))

describe('useKeyApi provider profiles', () => {
  afterEach(() => {
    apiMock.requestJson.mockReset()
  })

  it('reads provider connection profiles from /providers', async () => {
    apiMock.requestJson.mockResolvedValueOnce([
      {
        id: 'openrouter',
        label: 'OpenRouter',
        status: 'NEEDS_CONNECTION',
        connectionProfiles: [
          {
            id: 'openrouter-oauth',
            label: 'OpenRouter OAuth',
            authMethod: 'OAUTH_PKCE',
            oauthEnabled: true
          }
        ],
        connections: []
      }
    ])

    const providers = await useKeyApi().getProviders()

    expect(apiMock.requestJson).toHaveBeenCalledWith('/providers', { method: 'GET' })
    expect(providers[0]?.connectionProfiles[0]).toMatchObject({
      id: 'openrouter-oauth',
      authMethod: 'OAUTH_PKCE',
      oauthEnabled: true
    })
  })

  it('posts connectionProfileId when saving provider credentials', async () => {
    apiMock.requestJson.mockResolvedValueOnce({ providerId: 'qwen', connectionProfileId: 'coding-plan' })

    await useKeyApi().saveProviderCredential('qwen', {
      connectionProfileId: 'coding-plan',
      authMethod: 'OPENAI_COMPATIBLE',
      apiKey: 'sk-qwen',
      baseUrl: 'https://coding-intl.dashscope.aliyuncs.com/v1',
      defaultModelId: 'qwen-coder-plus'
    })

    expect(apiMock.requestJson).toHaveBeenCalledWith('/provider-connections/qwen/api-key', {
      method: 'POST',
      body: JSON.stringify({
        connectionProfileId: 'coding-plan',
        authMethod: 'OPENAI_COMPATIBLE',
        apiKey: 'sk-qwen',
        baseUrl: 'https://coding-intl.dashscope.aliyuncs.com/v1',
        defaultModelId: 'qwen-coder-plus'
      })
    })
  })

  it('maps legacy providers and keeps the default profile payload optional', async () => {
    apiMock.requestJson.mockResolvedValueOnce({ providerId: 'openai', connectionProfileId: 'api-key' })

    await useKeyApi().saveKey('OPENAI', 'sk-openai')

    expect(apiMock.requestJson).toHaveBeenCalledWith('/provider-connections/openai/api-key', {
      method: 'POST',
      body: JSON.stringify({ apiKey: 'sk-openai' })
    })
  })

  it('posts connectionProfileId for OAuth and device-code flows', async () => {
    apiMock.requestJson
      .mockResolvedValueOnce({ mode: 'DEVICE_CODE', connectionProfileId: 'github-oauth' })
      .mockResolvedValueOnce({ mode: 'PENDING', connectionProfileId: 'github-oauth' })

    const api = useKeyApi()
    await api.startConnection('github-copilot', 'DEVICE_CODE', 'github-oauth')
    await api.completeDeviceConnection('github-copilot', 'device-123', 'github-oauth')

    expect(apiMock.requestJson).toHaveBeenNthCalledWith(
      1,
      '/provider-connections/github-copilot/connect',
      {
        method: 'POST',
        body: JSON.stringify({ authMethod: 'DEVICE_CODE', connectionProfileId: 'github-oauth' })
      }
    )
    expect(apiMock.requestJson).toHaveBeenNthCalledWith(
      2,
      '/provider-connections/github-copilot/device-code',
      {
        method: 'POST',
        body: JSON.stringify({ deviceCode: 'device-123', connectionProfileId: 'github-oauth' })
      }
    )
  })

  it('builds xAI subscription CLI connection requests as pure payloads', () => {
    expect(startConnectionRequest('xai', 'SUBSCRIPTION_CLI', 'grok-build-subscription')).toEqual({
      path: '/provider-connections/xai/connect',
      options: {
        method: 'POST',
        body: JSON.stringify({
          authMethod: 'SUBSCRIPTION_CLI',
          connectionProfileId: 'grok-build-subscription'
        })
      }
    })
    expect(completeDeviceConnectionRequest('xai', 'grok-login-123', 'grok-build-subscription')).toEqual({
      path: '/provider-connections/xai/device-code',
      options: {
        method: 'POST',
        body: JSON.stringify({
          deviceCode: 'grok-login-123',
          connectionProfileId: 'grok-build-subscription'
        })
      }
    })
  })
})
