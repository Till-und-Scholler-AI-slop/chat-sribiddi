import { useChatApi } from '~/composables/useChatApi'

export type UsageBucket = {
  key: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  estimatedCost: number
}

export type UsageSummary = {
  totalInputTokens: number
  totalOutputTokens: number
  totalTokens: number
  totalEstimatedCost: number
}

export type UsageResponse = {
  buckets: UsageBucket[]
  summary: UsageSummary
}

export function useUsageApi() {
  const { requestJson, baseUrl } = useChatApi()

  const fetchUsage = async (params: {
    from?: string
    to?: string
    groupBy?: string
  } = {}) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    if (params.groupBy) query.set('groupBy', params.groupBy)
    const qs = query.toString()
    const path = qs ? `/api/usage?${qs}` : '/api/usage'
    return requestJson<UsageResponse>(path, { method: 'GET' })
  }

  const getExportUrl = (params: { from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    if (params.from) query.set('from', params.from)
    if (params.to) query.set('to', params.to)
    const qs = query.toString()
    return `${baseUrl.value}/api/usage/export${qs ? `?${qs}` : ''}`
  }

  return {
    fetchUsage,
    getExportUrl
  }
}
