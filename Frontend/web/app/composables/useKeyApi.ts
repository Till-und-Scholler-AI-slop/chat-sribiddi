import { useChatApi, type Provider } from './useChatApi'
import { PROVIDER_IDS } from '~/utils/model-utils'

type KeyStatus = {
  openai: boolean
  google: boolean
  claude: boolean
  openrouter: boolean
  brave_search: boolean
}

export type ProviderAuthMethod =
  | 'API_KEY'
  | 'OAUTH_PKCE'
  | 'OAUTH_AUTH_CODE'
  | 'DEVICE_CODE'
  | 'SERVICE_ACCOUNT'
  | 'LOCAL_ENDPOINT'
  | 'OPENAI_COMPATIBLE'
  | 'SUBSCRIPTION_CLI'
  | 'CLI_SESSION'
  | 'CUSTOM_HTTP'

export type ProviderConnectionStatus =
  | 'READY'
  | 'NEEDS_CONNECTION'
  | 'NEEDS_LOCAL_ENDPOINT'
  | 'EXPERIMENTAL'
  | 'DISABLED'
  | 'ERROR'

export type ProviderConnectionSummary = {
  id: number
  providerId: string
  authMethod: ProviderAuthMethod
  status: ProviderConnectionStatus
  displayName?: string | null
  providerAccountLabel?: string | null
  defaultConnection: boolean
  tokenExpiresAt?: string | null
  lastValidatedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  defaultModelId?: string | null
}

export type ProviderCatalogItem = {
  id: string
  label: string
  category: string
  chatProvider: boolean
  localOnly: boolean
  experimental: boolean
  execution: string
  status: ProviderConnectionStatus
  authMethods: ProviderAuthMethod[]
  connections: ProviderConnectionSummary[]
}

type ConnectStartResponse = {
  mode: 'REDIRECT' | 'UNAVAILABLE' | string
  authorizationUrl?: string | null
  state?: string | null
  expiresAt?: string | null
  authMethod: ProviderAuthMethod
  message?: string | null
}

export type SaveProviderCredentialInput = {
  apiKey?: string
  baseUrl?: string
  authMethod?: ProviderAuthMethod
  headers?: Record<string, string>
  queryParams?: Record<string, string>
  defaultModelId?: string
  displayName?: string
}

const providerId = (provider: Provider | 'BRAVE_SEARCH' | string) => {
  if (provider === 'BRAVE_SEARCH') return 'brave-search'
  if (typeof provider === 'string' && !(provider in PROVIDER_IDS)) return provider
  return PROVIDER_IDS[provider]
}

const isReady = (providers: ProviderCatalogItem[], id: string) =>
  providers.find(provider => provider.id === id)?.status === 'READY'

export function useKeyApi() {
  const { requestJson } = useChatApi()

  const getProviders = async () => {
    return await requestJson<ProviderCatalogItem[]>('/providers', { method: 'GET' }) ?? []
  }

  const getConnections = async () => {
    return await requestJson<ProviderConnectionSummary[]>('/provider-connections', { method: 'GET' }) ?? []
  }

  const getStatus = async () => {
    const providers = await getProviders()
    const openai = isReady(providers, 'openai')
    const google = isReady(providers, 'google')
    const claude = isReady(providers, 'anthropic')
    const openrouter = isReady(providers, 'openrouter')
    const brave_search = isReady(providers, 'brave-search')

    return { openai, google, claude, openrouter, brave_search } satisfies KeyStatus
  }

  const saveKey = async (provider: Provider | 'BRAVE_SEARCH', apiKey: string) => {
    return saveProviderCredential(provider, { apiKey })
  }

  const saveProviderCredential = async (
    provider: Provider | 'BRAVE_SEARCH' | string,
    credential: SaveProviderCredentialInput
  ) => {
    return requestJson<ProviderConnectionSummary>(`/provider-connections/${providerId(provider)}/api-key`, {
      method: 'POST',
      body: JSON.stringify(credential)
    })
  }

  const deleteKey = async (provider: Provider | 'BRAVE_SEARCH') => {
    return requestJson<unknown>(`/provider-connections/${providerId(provider)}/api-key`, { method: 'DELETE' })
  }

  const startConnection = async (
    provider: Provider | 'BRAVE_SEARCH',
    authMethod: ProviderAuthMethod
  ) => {
    return requestJson<ConnectStartResponse>(`/provider-connections/${providerId(provider)}/connect`, {
      method: 'POST',
      body: JSON.stringify({ authMethod })
    })
  }

  return {
    getProviders,
    getConnections,
    getStatus,
    saveKey,
    saveProviderCredential,
    deleteKey,
    startConnection
  }
}
