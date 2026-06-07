import type { ModelOption } from '~/composables/useChatApi'
import type { Provider } from '~/utils/model-utils'

export const PROVIDER_LABELS: Record<Provider, string> = {
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  CLAUDE: 'Claude',
  OPENROUTER: 'OpenRouter'
}

export function providerLabel(provider: Provider | string): string {
  return PROVIDER_LABELS[provider as Provider] || provider
}

export function formatTokenCount(count: number | null | undefined): string {
  if (count == null) return '\u2014'
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`
  return count.toLocaleString()
}

export function formatMimeType(mimeType: string): string {
  const subType = mimeType.split('/').pop() || mimeType
  return subType.toUpperCase()
}

export function optionPriceLabel(option: ModelOption): string | null {
  return formatRatePerMillion(option.promptPrice)
}

export function formatRatePerMillion(priceStr: string | null | undefined): string | null {
  if (!priceStr) return null
  const rate = Number(priceStr)
  if (Number.isNaN(rate)) return null
  if (rate === 0) return '$0'
  const perMillion = rate * 1_000_000
  return perMillion >= 1 ? `$${perMillion.toFixed(2)}` : `$${perMillion.toFixed(4)}`
}
