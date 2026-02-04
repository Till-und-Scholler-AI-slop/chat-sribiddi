type KeyStatus = {
  openai: boolean
  google: boolean
}

type KeyPayload = {
  openAiApiKey?: string
  googleApiKey?: string
}

export function useKeyApi() {
  const { requestJson } = useChatApi()

  const getStatus = async () => {
    return requestJson<KeyStatus>('/user/keys', { method: 'GET' })
  }

  const saveKeys = async (payload: KeyPayload) => {
    return requestJson<void>('/user/keys', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  }

  const deleteKeys = async () => {
    return requestJson<void>('/user/keys', { method: 'DELETE' })
  }

  return {
    getStatus,
    saveKeys,
    deleteKeys
  }
}
