import type { Plan } from './useEntitlements'

/**
 * Plan tier that can be purchased via Stripe Checkout. {@code FREE} is the
 * default tier and is not a valid input for {@link useBillingApi.createCheckout},
 * so the checkout-facing type excludes it.
 */
export type BillingPlan = Exclude<Plan, 'FREE'>

/**
 * Subscription billing interval. Mirrors the Java enum
 * {@code com.rft.chat_sribiddi.billing.StripeProperties.Interval}.
 */
export type BillingInterval = 'MONTH' | 'YEAR'

export type CheckoutRequest = {
  plan: BillingPlan
  interval: BillingInterval
}

/**
 * Response returned by {@code POST /billing/checkout-session} and
 * {@code POST /billing/portal-session}. The backend wraps the Stripe-hosted URL
 * in a {@code { url }} envelope (see {@code BillingController.UrlResponse}).
 */
export type UrlResponse = {
  url: string
}

export type CheckoutResponse = UrlResponse
export type PortalResponse = UrlResponse

/**
 * Centralised client for the Stripe-backed billing endpoints exposed under
 * {@code /billing/*}. Mirrors the pattern used by {@link useChatApi} and
 * {@link useKeyApi}: all HTTP access goes through {@code requestJson} so CSRF
 * handling and base-URL resolution stay in one place.
 *
 * Note: the {@code /billing/me} endpoint is intentionally not exposed here —
 * {@link useEntitlements} already owns that call with SSR-safe caching.
 */
export function useBillingApi() {
  const { requestJson } = useChatApi()

  /**
   * Starts a new Stripe Checkout session for the given plan/interval. Returns
   * the Stripe-hosted URL the caller should redirect the browser to.
   */
  const createCheckout = async (
    plan: BillingPlan,
    interval: BillingInterval = 'MONTH'
  ): Promise<CheckoutResponse> => {
    return requestJson<CheckoutResponse>('/billing/checkout-session', {
      method: 'POST',
      body: JSON.stringify({ plan, interval } satisfies CheckoutRequest)
    })
  }

  /**
   * Creates a Stripe Customer Portal session so the user can manage (cancel,
   * switch plan, update payment method) an existing subscription. Returns the
   * Stripe-hosted portal URL.
   */
  const createPortalSession = async (): Promise<PortalResponse> => {
    return requestJson<PortalResponse>('/billing/portal-session', {
      method: 'POST'
    })
  }

  return {
    createCheckout,
    createPortalSession
  }
}
