import type { Provider, ModelOption } from '~/composables/useChatApi'
import { formatRatePerMillion as _formatRatePerMillion } from '~/composables/chat-helpers'

export const PROVIDER_LABELS: Record<Provider, string> = {
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  CLAUDE: 'Claude',
  OPENROUTER: 'OpenRouter'
}

export function providerLabel(provider: Provider): string {
  return PROVIDER_LABELS[provider]
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
  return _formatRatePerMillion(option.promptPrice)
}

export function formatRatePerMillion(priceStr: string | null | undefined): string | null {
  return _formatRatePerMillion(priceStr)
}
