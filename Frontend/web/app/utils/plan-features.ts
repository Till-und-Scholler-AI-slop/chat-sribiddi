/**
 * Number of bullet points to render per plan on the pricing / billing pages.
 *
 * Shared between {@code pages/pricing.vue} and {@code pages/billing.vue} so
 * both pages stay in sync with the {@code pricing.plans.<key>.features.*}
 * i18n entries. When the feature list grows/shrinks for a tier, update both
 * the JSON entries and this map.
 */
export const PLAN_FEATURE_COUNTS = {
  free: 4,
  premium: 6,
  max: 6
} as const

export type PlanFeatureKey = keyof typeof PLAN_FEATURE_COUNTS
