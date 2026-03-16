import type { Provider } from './useChatApi'

type KeyStatus = {
  openai: boolean
  google: boolean
  claude: boolean
  openrouter: boolean
}

const providerPath = (provider: Provider) => `/apikeys/${provider.toLowerCase()}`

export function useKeyApi() {
  const { requestJson } = useChatApi()

  const getStatus = async () => {
    const [openai, google, claude, openrouter] = await Promise.all([
      requestJson<boolean>(`${providerPath('OPENAI')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('GOOGLE')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('CLAUDE')}/exists`, { method: 'GET' }),
      requestJson<boolean>(`${providerPath('OPENROUTER')}/exists`, { method: 'GET' })
    ])

    return { openai, google, claude, openrouter } satisfies KeyStatus
  }

  const saveKey = async (provider: Provider, apiKey: string) => {
    return requestJson<void>(providerPath(provider), {
      method: 'PUT',
      body: JSON.stringify({ apiKey })
    })
  }

  const deleteKey = async (provider: Provider) => {
    return requestJson<void>(providerPath(provider), { method: 'DELETE' })
  }

  return {
    getStatus,
    saveKey,
    deleteKey
  }
}
