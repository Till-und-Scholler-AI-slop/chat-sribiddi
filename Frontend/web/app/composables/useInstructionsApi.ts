type UserSettings = {
  customInstructions: string | null
}

export function useInstructionsApi() {
  const { requestJson } = useChatApi()

  const getCustomInstructions = async (): Promise<string | null> => {
    const result = await requestJson<UserSettings>('/account/settings', { method: 'GET' })
    return result?.customInstructions ?? null
  }

  const saveCustomInstructions = async (customInstructions: string | null) => {
    return requestJson<unknown>('/account/settings', {
      method: 'PUT',
      body: JSON.stringify({ customInstructions })
    })
  }

  return {
    getCustomInstructions,
    saveCustomInstructions
  }
}
