import type { ModelOption } from '~/composables/useChatApi'
import type { Provider } from '~/utils/model-utils'
import { PROVIDERS } from '~/utils/model-utils'

export async function fetchAllProviderModels(
  fetchModels: (provider: Provider, expert: boolean) => Promise<ModelOption[]>,
  expertMode: boolean
): Promise<{ models: ModelOption[]; errors: string[] }> {
  const results = await Promise.allSettled(
    PROVIDERS.map(provider => fetchModels(provider, expertMode))
  )

  const models: ModelOption[] = []
  const errors: string[] = []

  for (const result of results) {
    if (result.status === 'fulfilled') {
      models.push(...result.value)
    } else {
      const msg = result.reason instanceof Error ? result.reason.message : String(result.reason)
      errors.push(msg)
      console.error('Failed to load models for a provider:', msg)
    }
  }

  return { models, errors }
}
