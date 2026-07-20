import { describe, expect, it } from 'vitest'
import {
  GUEST_CAPABILITY_DEFAULTS,
  normalizeChatCapabilities
} from '../useAccessCapabilities'

describe('normalizeChatCapabilities', () => {
  it('accepts valid server limits', () => {
    expect(normalizeChatCapabilities({
      maxAttachments: 3,
      maxAttachmentBytes: 1_000,
      maxTotalAttachmentBytes: 2_000,
      maxDocumentBytes: 4_000
    }, GUEST_CAPABILITY_DEFAULTS)).toEqual({
      maxAttachments: 3,
      maxAttachmentBytes: 1_000,
      maxTotalAttachmentBytes: 2_000,
      maxDocumentBytes: 4_000
    })
  })

  it('falls back safely for invalid server values', () => {
    expect(normalizeChatCapabilities({
      maxAttachments: -1,
      maxAttachmentBytes: 0,
      maxTotalAttachmentBytes: Number.NaN,
      maxDocumentBytes: -5
    }, GUEST_CAPABILITY_DEFAULTS)).toEqual(GUEST_CAPABILITY_DEFAULTS)
  })
})
