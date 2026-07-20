import type { ChatCapabilities } from '~/composables/useChatApi'

const MIB = 1024 * 1024

export const GUEST_CAPABILITY_DEFAULTS: ChatCapabilities = {
  maxAttachments: 2,
  maxAttachmentBytes: 2 * MIB,
  maxTotalAttachmentBytes: 4 * MIB,
  maxDocumentBytes: 5 * MIB
}

export const AUTHENTICATED_CAPABILITY_DEFAULTS: ChatCapabilities = {
  maxAttachments: 5,
  maxAttachmentBytes: 5 * MIB,
  maxTotalAttachmentBytes: 20 * MIB,
  maxDocumentBytes: 5 * MIB
}

type CapabilityCoordinator = {
  generation: number
  key: string | null
  promise: Promise<ChatCapabilities> | null
}

const coordinators = new WeakMap<object, CapabilityCoordinator>()

function coordinatorFor(key: object) {
  let coordinator = coordinators.get(key)
  if (!coordinator) {
    coordinator = { generation: 0, key: null, promise: null }
    coordinators.set(key, coordinator)
  }
  return coordinator
}

function positiveInteger(value: unknown, fallback: number, allowZero = false) {
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isInteger(numeric) || (allowZero ? numeric < 0 : numeric <= 0)) return fallback
  return numeric
}

export function normalizeChatCapabilities(
  value: Partial<ChatCapabilities> | null | undefined,
  fallback: ChatCapabilities
): ChatCapabilities {
  return {
    maxAttachments: positiveInteger(value?.maxAttachments, fallback.maxAttachments, true),
    maxAttachmentBytes: positiveInteger(value?.maxAttachmentBytes, fallback.maxAttachmentBytes),
    maxTotalAttachmentBytes: positiveInteger(
      value?.maxTotalAttachmentBytes,
      fallback.maxTotalAttachmentBytes
    ),
    maxDocumentBytes: positiveInteger(value?.maxDocumentBytes, fallback.maxDocumentBytes)
  }
}

export function useAccessCapabilities() {
  const app = useNuxtApp()
  const coordinator = coordinatorFor(app)
  const { status } = useAuth()
  const { baseUrl, fetchChatCapabilities } = useChatApi()
  const capabilities = useState<ChatCapabilities>(
    'chat-capabilities',
    () => GUEST_CAPABILITY_DEFAULTS
  )
  const loadedKey = useState<string | null>('chat-capabilities-key', () => null)

  const fallback = () => status.value === 'authenticated'
    ? AUTHENTICATED_CAPABILITY_DEFAULTS
    : GUEST_CAPABILITY_DEFAULTS

  watch(
    () => `${baseUrl.value}|${status.value}`,
    (requestKey) => {
      if (loadedKey.value === requestKey || coordinator.key === requestKey) return
      coordinator.generation++
      coordinator.key = null
      coordinator.promise = null
      loadedKey.value = null
      capabilities.value = { ...fallback() }
    }
  )

  const ensureCapabilities = async (force = false) => {
    const requestKey = `${baseUrl.value}|${status.value}`
    if (!force && loadedKey.value === requestKey) return capabilities.value
    if (!force && coordinator.promise && coordinator.key === requestKey) {
      return coordinator.promise
    }

    const generation = ++coordinator.generation
    coordinator.key = requestKey
    const promise = (async () => {
      try {
        const loaded = await fetchChatCapabilities()
        if (generation === coordinator.generation && coordinator.key === requestKey) {
          capabilities.value = normalizeChatCapabilities(loaded, fallback())
          loadedKey.value = requestKey
        }
      } catch {
        if (generation === coordinator.generation && coordinator.key === requestKey) {
          capabilities.value = { ...fallback() }
          loadedKey.value = requestKey
        }
      }
      return capabilities.value
    })()

    coordinator.promise = promise
    try {
      return await promise
    } finally {
      if (coordinator.promise === promise) coordinator.promise = null
    }
  }

  return { capabilities, ensureCapabilities }
}
