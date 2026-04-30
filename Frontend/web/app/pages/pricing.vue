<script setup lang="ts">
import { computed, ref } from 'vue'
import type { BillingPlan } from '~/composables/useBillingApi'
import { PLAN_FEATURE_COUNTS } from '~/utils/plan-features'

const { t } = useI18n()
const toast = useToast()
const tt = useTranslatedError()
const { status, checking, checkAuth } = useAuth()
const { plan: currentPlan, ensureLoaded } = usePlan()
const { createCheckout } = useBillingApi()

definePageMeta({
  layout: 'auth'
})

// Kick off an auth check so the CTA labels reflect the real session state.
// `/pricing` is a public page, so `auth.global` does not run the check.
if (import.meta.client) {
  void (async () => {
    try {
      const result = await checkAuth()
      if (result === 'authenticated') {
        await ensureLoaded()
      }
    } catch {
      // Non-fatal: the CTAs degrade gracefully to the unauthenticated "Sign in
      // to upgrade" state if the auth/entitlements probes fail.
    }
  })()
}

type PlanKey = 'free' | 'premium' | 'max'

interface PlanDef {
  key: PlanKey
  billingPlan: BillingPlan | null // null for FREE; no checkout possible
  featureCount: number
  highlighted: boolean
}

const PLANS: readonly PlanDef[] = [
  { key: 'free', billingPlan: null, featureCount: PLAN_FEATURE_COUNTS.free, highlighted: false },
  { key: 'premium', billingPlan: 'PREMIUM', featureCount: PLAN_FEATURE_COUNTS.premium, highlighted: true },
  { key: 'max', billingPlan: 'MAX', featureCount: PLAN_FEATURE_COUNTS.max, highlighted: false }
]

const isAuthenticated = computed(() => status.value === 'authenticated')

function isCurrentPlan(plan: PlanDef): boolean {
  if (!isAuthenticated.value) return false
  if (plan.key === 'free') return currentPlan.value === 'FREE'
  if (plan.key === 'premium') return currentPlan.value === 'PREMIUM'
  if (plan.key === 'max') return currentPlan.value === 'MAX'
  return false
}

const checkoutLoading = ref<PlanKey | null>(null)

async function handleCta(plan: PlanDef) {
  // The page is public, so on a fresh load `status` may still be `'unknown'`
  // when the user clicks a CTA. Resolve the real session state first, otherwise
  // a signed-in user could be incorrectly bounced to `/login`.
  if (status.value === 'unknown') {
    try {
      const result = await checkAuth()
      if (result === 'authenticated') {
        await ensureLoaded()
      }
    } catch {
      // Treat a failed probe as unauthenticated — the redirect to /login below
      // still gives the user a path forward.
    }
  }

  // Unauthenticated users → send to /login regardless of which plan they clicked.
  if (!isAuthenticated.value) {
    await navigateTo('/login')
    return
  }

  // No checkout for Free (it's the default tier) or the plan the user already has.
  if (!plan.billingPlan || isCurrentPlan(plan)) return

  checkoutLoading.value = plan.key
  try {
    const { url } = await createCheckout(plan.billingPlan)
    if (url && import.meta.client) {
      window.location.assign(url)
      return
    }
    // Defensive: backend should always return a URL here, but surface a toast
    // rather than silently clearing the loading state if it doesn't.
    toast.add({
      title: t('pricing.checkoutFailedTitle'),
      color: 'error'
    })
  } catch (err) {
    toast.add({
      title: t('pricing.checkoutFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    checkoutLoading.value = null
  }
}

function ctaLabel(plan: PlanDef): string {
  // Free is the default tier and has no checkout path, so it's always labeled
  // "Current" regardless of session state.
  if (plan.key === 'free') return t('pricing.ctaCurrent')
  if (isCurrentPlan(plan)) return t('pricing.ctaCurrent')
  if (!isAuthenticated.value) return t('pricing.ctaSignInToUpgrade')
  return t('pricing.ctaUpgrade')
}

function ctaDisabled(plan: PlanDef): boolean {
  // Free tier never has a checkout target.
  if (plan.key === 'free') return true
  // While the initial auth probe is running we don't yet know whether the user
  // is on this plan, so disable the CTA to avoid showing a clickable
  // "Upgrade"/"Sign in" that might be wrong for a split second.
  if (checking.value) return true
  return isCurrentPlan(plan)
}

function planFeatures(plan: PlanDef): string[] {
  const features: string[] = []
  for (let i = 0; i < plan.featureCount; i++) {
    features.push(t(`pricing.plans.${plan.key}.features.${i}`))
  }
  return features
}
</script>

<template>
  <div class="mx-auto w-full max-w-5xl space-y-8">
    <div class="space-y-2 text-center">
      <h1 class="text-3xl font-semibold">{{ t('pricing.title') }}</h1>
      <p class="text-sm text-gray-600 dark:text-gray-400">{{ t('pricing.subtitle') }}</p>
    </div>

    <div class="grid grid-cols-1 gap-6 md:grid-cols-3">
      <UCard
        v-for="plan in PLANS"
        :key="plan.key"
        :class="[
          'flex h-full flex-col',
          plan.highlighted ? 'ring-2 ring-primary-500' : ''
        ]"
      >
        <div class="flex h-full flex-col gap-4">
          <div class="space-y-2">
            <div class="flex items-center justify-between gap-2">
              <h2 class="text-xl font-semibold">
                {{ t(`pricing.plans.${plan.key}.name`) }}
              </h2>
              <UBadge
                v-if="plan.highlighted"
                color="primary"
                variant="subtle"
                :label="t('pricing.recommended')"
              />
              <UBadge
                v-else-if="isCurrentPlan(plan)"
                color="neutral"
                variant="subtle"
                :label="t('pricing.currentPlan')"
              />
            </div>

            <div class="flex items-baseline gap-1">
              <span class="text-3xl font-semibold">
                {{ t(`pricing.plans.${plan.key}.price`) }}
              </span>
              <span
                v-if="plan.key !== 'free'"
                class="text-sm text-gray-600 dark:text-gray-400"
              >
                {{ t('pricing.perMonth') }}
              </span>
            </div>

            <p class="text-sm text-gray-600 dark:text-gray-400">
              {{ t(`pricing.plans.${plan.key}.description`) }}
            </p>
          </div>

          <ul class="flex-1 space-y-2 text-sm">
            <li
              v-for="(feature, idx) in planFeatures(plan)"
              :key="idx"
              class="flex items-start gap-2"
            >
              <UIcon
                name="i-heroicons-check-circle"
                class="mt-0.5 h-5 w-5 flex-none text-primary-500"
              />
              <span>{{ feature }}</span>
            </li>
          </ul>

          <UButton
            block
            :color="plan.highlighted ? 'primary' : 'neutral'"
            :variant="plan.highlighted ? 'solid' : 'outline'"
            :disabled="ctaDisabled(plan)"
            :loading="checkoutLoading === plan.key"
            :label="ctaLabel(plan)"
            @click="handleCta(plan)"
          />
        </div>
      </UCard>
    </div>
  </div>
</template>
