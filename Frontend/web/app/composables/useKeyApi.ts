import type { Provider } from './useChatApi'

type KeyStatus = {
  openai: boolean
  google: boolean
  claude: boolean
  openrouter: boolean
  brave_search: boolean
}

const providerPath = (provider: Provider | 'BRAVE_SEARCH') => `/apikeys/${provider.toLowerCase()}`

export function useKeyApi() {
  const { requestJson } = useChatApi()

  const getStatus = async () => {
    const [openai, google, claude, openrouter, brave_search] = await Promise.all([
      requestJson<boolean>(`${providerPath('OPENAI')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('GOOGLE')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('CLAUDE')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('OPENROUTER')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('BRAVE_SEARCH')}/exists`, { method: 'GET' })
    ])

    return { openai, google, claude, openrouter, brave_search } satisfies KeyStatus
  }

  const saveKey = async (provider: Provider | 'BRAVE_SEARCH', apiKey: string) => {
    return requestJson<unknown>(providerPath(provider), {
      method: 'PUT',
      body: JSON.stringify({ apiKey })
    })
  }

  const deleteKey = async (provider: Provider | 'BRAVE_SEARCH') => {
    return requestJson<unknown>(providerPath(provider), { method: 'DELETE' })
  }

  return {
    getStatus,
    saveKey,
    deleteKey
  }
}
