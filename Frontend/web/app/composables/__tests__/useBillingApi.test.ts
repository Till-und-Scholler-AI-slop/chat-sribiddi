import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBillingApi } from '../useBillingApi'

function installNuxtStubs(requestJsonMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('useChatApi', () => ({ requestJson: requestJsonMock }))
}

describe('useBillingApi', () => {
  let requestJson: ReturnType<typeof vi.fn>

  beforeEach(() => {
    requestJson = vi.fn()
    installNuxtStubs(requestJson)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  describe('createCheckout', () => {
    it('POSTs to /billing/checkout-session with plan+interval in the body', async () => {
      requestJson.mockResolvedValueOnce({ url: 'https://checkout.stripe.com/session_123' })

      const { createCheckout } = useBillingApi()
      const response = await createCheckout('PREMIUM', 'YEAR')

      expect(requestJson).toHaveBeenCalledTimes(1)
      expect(requestJson).toHaveBeenCalledWith('/billing/checkout-session', {
        method: 'POST',
        body: JSON.stringify({ plan: 'PREMIUM', interval: 'YEAR' })
      })
      expect(response).toEqual({ url: 'https://checkout.stripe.com/session_123' })
    })

    it('defaults the interval to MONTH when only a plan is provided', async () => {
      requestJson.mockResolvedValueOnce({ url: 'https://checkout.stripe.com/session_456' })

      const { createCheckout } = useBillingApi()
      await createCheckout('MAX')

      expect(requestJson).toHaveBeenCalledWith('/billing/checkout-session', {
        method: 'POST',
        body: JSON.stringify({ plan: 'MAX', interval: 'MONTH' })
      })
    })

    it('propagates errors thrown by requestJson', async () => {
      requestJson.mockRejectedValueOnce(new Error('stripe down'))

      const { createCheckout } = useBillingApi()
      await expect(createCheckout('PREMIUM')).rejects.toThrow('stripe down')
    })
  })

  describe('createPortalSession', () => {
    it('POSTs to /billing/portal-session with no body', async () => {
      requestJson.mockResolvedValueOnce({ url: 'https://billing.stripe.com/portal_abc' })

      const { createPortalSession } = useBillingApi()
      const response = await createPortalSession()

      expect(requestJson).toHaveBeenCalledTimes(1)
      expect(requestJson).toHaveBeenCalledWith('/billing/portal-session', {
        method: 'POST'
      })
      expect(response).toEqual({ url: 'https://billing.stripe.com/portal_abc' })
    })

    it('propagates errors thrown by requestJson', async () => {
      requestJson.mockRejectedValueOnce(new Error('no customer'))

      const { createPortalSession } = useBillingApi()
      await expect(createPortalSession()).rejects.toThrow('no customer')
    })
  })
})
