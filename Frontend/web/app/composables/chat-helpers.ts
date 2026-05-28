import type { ChatStatus, UIMessage } from 'ai'
import type { SendMessageResponse, TokenUsage, ToolCallLog } from '~/composables/useChatApi'

export { favoriteModelKey } from '~/utils/model-utils'

export const messageId = () =>
  globalThis.crypto?.randomUUID?.() ||
  `msg-${Date.now()}-${Math.random().toString(16).slice(2)}`

function isRecord(candidate: unknown): candidate is Record<string, unknown> {
  return typeof candidate === 'object' && candidate !== null
}

export function extractAssistantText(response: unknown): string {
  const pick = (candidate: unknown): string | null => {
    if (!candidate) return null
    if (typeof candidate === 'string') return candidate

    if (Array.isArray(candidate)) {
      for (const item of candidate) {
        const found = pick(item)
        if (found) return found
      }
      return null
    }

    if (!isRecord(candidate)) return null

    if (typeof candidate.text === 'string') return candidate.text
    if (typeof candidate.content === 'string') return candidate.content

    if (Array.isArray(candidate.content)) {
      const found = pick(candidate.content)
      if (found) return found
    }

    if (candidate.output) {
      const found = pick(candidate.output)
      if (found) return found
    }

    if (candidate.message) {
      const found = pick(candidate.message)
      if (found) return found
    }

    if (candidate.result) {
      const found = pick(candidate.result)
      if (found) return found
    }

    if (Array.isArray(candidate.results)) {
      const found = pick(candidate.results)
      if (found) return found
    }

    return null
  }

  return pick(response)?.trim() || 'No response received.'
}

export function parseAttachmentContext(context: string): string[] {
  const prefix = 'attachments:'
  if (!context.startsWith(prefix)) return []
  const raw = context.slice(prefix.length)
  if (!raw) return []
  return raw.split(',').map(s => s.trim()).filter(Boolean)
}

export type MessageCost = {
  promptCost: number | null
  completionCost: number | null
  requestCost: number | null
  totalCost: number
}

export type MessagePricing = {
  promptPrice: string | null
  completionPrice: string | null
  requestPrice: string | null
}

export type EstimatedCostBreakdown = {
  inputTokens: number
  estimatedOutputTokens: number
  promptCost: number | null
  completionCost: number | null
  totalCost: number
}

const MESSAGE_OVERHEAD_TOKENS = 4
const FILE_PART_TOKENS = 12
const DEFAULT_ESTIMATED_OUTPUT_TOKENS = 256

/**
 * Calculates the estimated cost for a message based on token usage and model pricing.
 * Pricing values are per-token strings from the OpenRouter API (e.g. "0.000005").
 * Returns null when no pricing or no usage data is available.
 */
function parsePrice(value: string | null | undefined): number {
  return value ? Number(value) : NaN
}

export function estimateTextTokens(text: string): number {
  const normalized = text.trim()
  if (!normalized) return 0
  return Math.max(1, Math.ceil(normalized.length / 4))
}

function estimateMessageTokens(message: Pick<UIMessage, 'parts'>): number {
  const textTokens = estimateTextTokens(getFirstTextContent(message as UIMessage))
  const filePartCount = message.parts.filter(part => part.type === 'file').length

  if (textTokens === 0 && filePartCount === 0) {
    return 0
  }

  return textTokens + MESSAGE_OVERHEAD_TOKENS + (filePartCount * FILE_PART_TOKENS)
}

export function estimateConversationTokens(
  messages: Array<Pick<UIMessage, 'parts'>>,
  pendingPrompt: string,
  pendingAttachmentCount = 0
): number {
  const historyTokens = messages.reduce((total, message) => total + estimateMessageTokens(message), 0)
  const pendingPromptTokens = estimateTextTokens(pendingPrompt)
  const pendingAttachmentTokens = pendingAttachmentCount > 0 ? pendingAttachmentCount * FILE_PART_TOKENS : 0

  if (pendingPromptTokens === 0 && pendingAttachmentTokens === 0) {
    return historyTokens
  }

  return historyTokens + pendingPromptTokens + MESSAGE_OVERHEAD_TOKENS + pendingAttachmentTokens
}

export function estimateAssistantOutputTokens(
  messages: Array<Pick<UIMessage, 'id' | 'role' | 'parts'>>,
  tokenUsageByMessageId: Record<string, TokenUsage> = {}
): number {
  const assistantTokenEstimates = messages
    .filter(message => message.role === 'assistant')
    .map(message => {
      const generatedTokens = tokenUsageByMessageId[message.id]?.generationTokens
      return generatedTokens ?? estimateTextTokens(getFirstTextContent(message as UIMessage))
    })
    .filter(tokens => tokens > 0)

  if (assistantTokenEstimates.length === 0) {
    return DEFAULT_ESTIMATED_OUTPUT_TOKENS
  }

  const total = assistantTokenEstimates.reduce((sum, tokens) => sum + tokens, 0)
  return Math.max(1, Math.round(total / assistantTokenEstimates.length))
}

export function estimatePreSendCost(
  messages: Array<Pick<UIMessage, 'id' | 'role' | 'parts'>>,
  pendingPrompt: string,
  model: { promptPrice?: string | null; completionPrice?: string | null } | null | undefined,
  tokenUsageByMessageId: Record<string, TokenUsage> = {},
  pendingAttachmentCount = 0
): EstimatedCostBreakdown | null {
  if (!model) return null

  const inputTokens = estimateConversationTokens(messages, pendingPrompt, pendingAttachmentCount)
  const estimatedOutputTokens = estimateAssistantOutputTokens(messages, tokenUsageByMessageId)
  const promptRate = parsePrice(model.promptPrice)
  const completionRate = parsePrice(model.completionPrice)

  const hasPromptPricing = !Number.isNaN(promptRate)
  const hasCompletionPricing = !Number.isNaN(completionRate)

  if (!hasPromptPricing && !hasCompletionPricing) {
    return null
  }

  const promptCost = hasPromptPricing ? inputTokens * promptRate : null
  const completionCost = hasCompletionPricing ? estimatedOutputTokens * completionRate : null
  const totalCost = (promptCost ?? 0) + (completionCost ?? 0)

  return {
    inputTokens,
    estimatedOutputTokens,
    promptCost,
    completionCost,
    totalCost
  }
}

export function calculateMessageCost(
  usage: TokenUsage | null,
  model: { promptPrice?: string | null; completionPrice?: string | null; requestPrice?: string | null } | null | undefined
): MessageCost | null {
  if (!usage || !model) return null

  const promptRate = parsePrice(model.promptPrice)
  const completionRate = parsePrice(model.completionPrice)
  const requestRate = parsePrice(model.requestPrice)

  const hasPromptPricing = !Number.isNaN(promptRate) && usage.promptTokens != null
  const hasCompletionPricing = !Number.isNaN(completionRate) && usage.generationTokens != null
  const hasRequestPricing = !Number.isNaN(requestRate) && requestRate > 0
  const hasAnyPricing = hasPromptPricing || hasCompletionPricing || hasRequestPricing

  if (!hasAnyPricing) return null

  const promptCost = hasPromptPricing ? usage.promptTokens! * promptRate : null
  const completionCost = hasCompletionPricing ? usage.generationTokens! * completionRate : null
  const requestCost = hasRequestPricing ? requestRate : null
  const totalCost = (promptCost ?? 0) + (completionCost ?? 0) + (requestCost ?? 0)

  return { promptCost, completionCost, requestCost, totalCost }
}

/**
 * Formats a dollar cost into a human-readable string.
 * Uses adaptive precision: ≥ $0.01 → 2 decimals, $0.0001–$0.01 → 4 decimals,
 * < $0.0001 → scientific notation (e.g. "$5.0e-5").
 * Returns '$0.00' for zero and '—' for non-finite inputs (NaN, Infinity).
 */
export function formatCost(cost: number): string {
  if (!Number.isFinite(cost)) return '—'
  if (cost === 0) return '$0.00'
  if (cost < 0.0001) return `$${cost.toExponential(1)}`
  if (cost < 0.01) return `$${cost.toFixed(4)}`
  return `$${cost.toFixed(2)}`
}

/**
 * Formats a per-token price string (from OpenRouter API) into a human-readable
 * rate per 1M tokens. Returns null when the input is missing or not a number.
 */
export function formatRatePerMillion(priceStr: string | null | undefined): string | null {
  if (!priceStr) return null
  const rate = Number(priceStr)
  if (Number.isNaN(rate)) return null
  if (rate === 0) return '$0'
  const perMillion = rate * 1_000_000
  return perMillion >= 1 ? `$${perMillion.toFixed(2)}` : `$${perMillion.toFixed(4)}`
}

export function parsePersistedMessageId(id: string | undefined): number | null {
  if (typeof id !== 'string') return null
  const match = id.match(/^msg-(\d+)$/)
  return match ? Number.parseInt(match[1], 10) : null
}

export function filterToolCallLogs(logs: ToolCallLog[], query: string): ToolCallLog[] {
  const normalized = query.trim().toLowerCase()
  if (!normalized) return logs
  return logs.filter(log => log.toolName.toLowerCase().includes(normalized))
}

export function extractTokenUsage(response: SendMessageResponse | null): TokenUsage | null {
  if (!response) return null
  const { promptTokens, generationTokens, totalTokens } = response
  if (promptTokens == null && generationTokens == null && totalTokens == null) return null
  return {
    promptTokens: promptTokens ?? null,
    generationTokens: generationTokens ?? null,
    totalTokens: totalTokens ?? null
  }
}

export function appendTextToMessage(message: UIMessage, chunk: string): UIMessage {
  let updated = false
  const parts = message.parts.map(part => {
    if (part.type !== 'text') return part
    updated = true
    return {
      ...part,
      text: `${part.text || ''}${chunk}`
    }
  })

  return updated
    ? { ...message, parts }
    : {
        ...message,
        parts: [...message.parts, { type: 'text', text: chunk }]
      }
}

export function getFirstTextContent(message: UIMessage): string {
  return message.parts.find(
    (part): part is { type: 'text'; text: string } => part.type === 'text'
  )?.text || ''
}

export function resolveStreamOutcome(
  status: {
    receivedDone: boolean
    receivedError: boolean
    receivedContent: boolean
  },
  currentError: string | null
): {
  status: ChatStatus
  error: string | null
} {
  if (status.receivedError) {
    return {
      status: 'error',
      error: currentError || 'Streaming error'
    }
  }

  if (!status.receivedDone) {
    return {
      status: 'error',
      error: status.receivedContent
        ? 'The response stream was interrupted before completion. Partial response shown.'
        : 'The response stream was interrupted before completion.'
    }
  }

  return {
    status: 'ready',
    error: currentError
  }
}
