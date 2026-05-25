<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { BillingPlan } from '~/composables/useBillingApi'
import { PLAN_FEATURE_COUNTS } from '~/utils/plan-features'

/** Window used by {@code GET /billing/usage} — must match {@code BillingController.USAGE_WINDOW}. */
const USAGE_WINDOW_DAYS = 30

const { t, locale } = useI18n()
const tt = useTranslatedError()
const toast = useToast()
const { requestJson } = useChatApi()
const { createCheckout, createPortalSession } = useBillingApi()
const {
  plan,
  caps,
  status: subscriptionStatus,
  periodEnd,
  cancelAtPeriodEnd,
  loaded,
  loading: entitlementsLoading,
  error: entitlementsError,
  ensureLoaded
} = useEntitlements()

definePageMeta({
  layout: 'default'
})

// ── Usage ──────────────────────────────────────────────────────────────
// The backend does not expose today's managed-call count, so we show the
// trailing 30-day aggregate surfaced by GET /billing/usage next to the
// per-plan daily cap. This matches the "Usage-Progress (daily cap)" AC while
// being honest about the window that the backend can actually report.

type UsageResponse = {
  since: string
  until: string
  totalCalls: number
  totalTokens: number
  totalCostMicroCents: number
}

const usage = ref<UsageResponse | null>(null)
const usageLoading = ref(false)

async function loadUsage() {
  usageLoading.value = true
  try {
    usage.value = await requestJson<UsageResponse>('/billing/usage', { method: 'GET' })
  } catch (err) {
    toast.add({
      title: t('billing.usageLoadFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    usageLoading.value = false
  }
}

onMounted(async () => {
  await Promise.allSettled([ensureLoaded(), loadUsage()])
})

// ── Daily cap ────────────────────────────────────────────────────────────
// Prefer the dailyCap reported by the backend; fall back to the per-plan
// defaults advertised on the pricing page so the progress bar renders even
// before /billing/me is extended to include caps.
const DAILY_CAP_DEFAULTS: Record<string, number> = {
  FREE: 50,
  PREMIUM: 500,
  MAX: 2000
}

const dailyCap = computed<number>(() => {
  const fromCaps = caps.value.dailyCap
  if (typeof fromCaps === 'number' && fromCaps > 0) return fromCaps
  return DAILY_CAP_DEFAULTS[plan.value] ?? 0
})

const usageCalls = computed<number>(() => usage.value?.totalCalls ?? 0)
/**
 * Effective cap to compare {@link usageCalls} (a trailing-30-day total) against.
 * We scale the daily cap up to a 30-day window so the progress bar is
 * meaningful for a 30-day message count and doesn't peg at 100% within a day
 * of normal use.
 */
const windowCap = computed<number>(() => dailyCap.value * USAGE_WINDOW_DAYS)
const usagePercent = computed<number>(() => {
  if (windowCap.value <= 0) return 0
  return Math.min(100, Math.round((usageCalls.value / windowCap.value) * 100))
})

// ── Plan & status display helpers ───────────────────────────────────────
const isFree = computed(() => plan.value === 'FREE')
const isPaid = computed(() => plan.value === 'PREMIUM' || plan.value === 'MAX')

const planLabel = computed<string>(() => {
  const key = plan.value.toLowerCase()
  return t(`pricing.plans.${key}.name`)
})

const statusBadgeColor = computed<'primary' | 'neutral' | 'warning' | 'error'>(() => {
  const s = subscriptionStatus.value
  if (!s) return isPaid.value ? 'primary' : 'neutral'
  if (s === 'active' || s === 'trialing') return 'primary'
  if (s === 'past_due' || s === 'unpaid') return 'warning'
  if (s === 'canceled' || s === 'incomplete_expired') return 'error'
  return 'neutral'
})

const statusLabel = computed<string>(() => {
  const s = subscriptionStatus.value
  if (!s) return isPaid.value ? t('billing.status.active') : t('billing.status.free')
  const key = `billing.status.${s.replace(/-/g, '_')}`
  // Fall back to the raw Stripe status when no translation exists for it.
  const translated = t(key)
  return translated === key ? s : translated
})

const formattedPeriodEnd = computed<string | null>(() => {
  const iso = periodEnd.value
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  try {
    return d.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })
  } catch {
    return d.toISOString().slice(0, 10)
  }
})

/**
 * Label for the {@link formattedPeriodEnd} date. Terminal statuses
 * ({@code canceled}/{@code incomplete_expired}) map to "Ended on" since the
 * subscription will not renew; {@code cancelAtPeriodEnd} maps to "Cancels on";
 * everything else (active / trialing / past_due / …) maps to "Renews on".
 */
const periodEndLabel = computed<string>(() => {
  const s = subscriptionStatus.value
  if (s === 'canceled' || s === 'incomplete_expired') return t('billing.endedOn')
  if (cancelAtPeriodEnd.value) return t('billing.cancelsOn')
  return t('billing.renewsOn')
})

// ── Upgrade / portal actions ────────────────────────────────────────────
const checkoutLoading = ref<BillingPlan | null>(null)
const portalLoading = ref(false)

async function handleUpgrade(targetPlan: BillingPlan) {
  checkoutLoading.value = targetPlan
  try {
    const { url } = await createCheckout(targetPlan)
    if (url && import.meta.client) {
      window.location.assign(url)
      return
    }
    toast.add({ title: t('billing.checkoutFailedTitle'), color: 'error' })
  } catch (err) {
    toast.add({
      title: t('billing.checkoutFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    checkoutLoading.value = null
  }
}

async function handleManageSubscription() {
  portalLoading.value = true
  try {
    const { url } = await createPortalSession()
    if (url && import.meta.client) {
      window.location.assign(url)
      return
    }
    toast.add({ title: t('billing.portalFailedTitle'), color: 'error' })
  } catch (err) {
    toast.add({
      title: t('billing.portalFailedTitle'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    portalLoading.value = false
  }
}

type UpgradeCard = {
  key: BillingPlan
  highlighted: boolean
}

const upgradeCards: readonly UpgradeCard[] = [
  { key: 'PREMIUM', highlighted: true },
  { key: 'MAX', highlighted: false }
]

function cardFeatures(card: UpgradeCard): string[] {
  const key = card.key.toLowerCase() as keyof typeof PLAN_FEATURE_COUNTS
  const prefix = `pricing.plans.${key}.features`
  const count = PLAN_FEATURE_COUNTS[key]
  const out: string[] = []
  for (let i = 0; i < count; i++) {
    out.push(t(`${prefix}.${i}`))
  }
  return out
}
</script>

<template>
  <div>
    <UDashboardPanel>
      <template #body>
        <UContainer class="py-10 max-w-4xl space-y-10">
        <!-- Page heading -->
        <header class="space-y-3">
          <h1 class="font-serif-display text-4xl sm:text-5xl text-gray-900 dark:text-gray-50">
            {{ t('billing.title') }}
          </h1>
          <p class="text-base text-gray-500 dark:text-gray-400 max-w-2xl">
            {{ t('billing.subtitle') }}
          </p>
        </header>

        <UAlert
          v-if="entitlementsError"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :title="t('billing.entitlementsLoadFailedTitle')"
          :description="tt(new Error(entitlementsError))"
        />

        <div v-if="!loaded && entitlementsLoading" class="space-y-4">
          <div class="skeleton-shimmer h-14" />
          <div class="skeleton-shimmer h-40" />
          <div class="skeleton-shimmer h-40" />
        </div>

        <template v-else>
          <!-- Current plan -->
          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="flex items-start justify-between gap-4 flex-wrap">
                <div class="space-y-1">
                  <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                    {{ t('billing.currentPlanHeading') }}
                  </h2>
                  <p class="text-sm text-gray-500 dark:text-gray-400">
                    {{ t('billing.currentPlanDescription') }}
                  </p>
                </div>
                <UBadge :color="statusBadgeColor" variant="soft" :label="statusLabel" />
              </div>

              <dl class="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
                <div>
                  <dt class="text-gray-500 dark:text-gray-400">{{ t('billing.planLabel') }}</dt>
                  <dd class="mt-1 font-semibold text-gray-900 dark:text-gray-50">{{ planLabel }}</dd>
                </div>
                <div v-if="formattedPeriodEnd">
                  <dt class="text-gray-500 dark:text-gray-400">
                    {{ periodEndLabel }}
                  </dt>
                  <dd class="mt-1 font-semibold text-gray-900 dark:text-gray-50">
                    {{ formattedPeriodEnd }}
                  </dd>
                </div>
              </dl>

              <p
                v-if="isPaid && cancelAtPeriodEnd"
                class="text-sm text-amber-600 dark:text-amber-400"
              >
                {{ t('billing.cancelAtPeriodEndNotice') }}
              </p>
            </div>
          </UCard>

          <!-- Subscription management -->
          <section class="space-y-4">
            <template v-if="isFree">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                  {{ t('billing.upgradeHeading') }}
                </h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('billing.upgradeDescription') }}
                </p>
              </div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                <UCard
                  v-for="card in upgradeCards"
                  :key="card.key"
                  class="flex h-full flex-col card-elevated"
                  :class="card.highlighted ? 'ring-2 ring-primary-500' : ''"
                >
                  <div class="flex h-full flex-col gap-4">
                    <div class="space-y-2">
                      <div class="flex items-center justify-between gap-2 flex-wrap">
                        <h3 class="text-xl font-semibold">
                          {{ t(`pricing.plans.${card.key.toLowerCase()}.name`) }}
                        </h3>
                        <UBadge
                          v-if="card.highlighted"
                          color="primary"
                          variant="subtle"
                          :label="t('pricing.recommended')"
                        />
                      </div>
                      <div class="flex items-baseline gap-1">
                        <span class="text-3xl font-semibold">
                          {{ t(`pricing.plans.${card.key.toLowerCase()}.price`) }}
                        </span>
                        <span class="text-sm text-gray-600 dark:text-gray-400">
                          {{ t('pricing.perMonth') }}
                        </span>
                      </div>
                      <p class="text-sm text-gray-600 dark:text-gray-400">
                        {{ t(`pricing.plans.${card.key.toLowerCase()}.description`) }}
                      </p>
                    </div>
                    <ul class="flex-1 space-y-2 text-sm">
                      <li
                        v-for="(feature, idx) in cardFeatures(card)"
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
                      :color="card.highlighted ? 'primary' : 'neutral'"
                      :variant="card.highlighted ? 'solid' : 'outline'"
                      :loading="checkoutLoading === card.key"
                      :disabled="checkoutLoading !== null"
                      :label="t('billing.upgradeCta')"
                      @click="handleUpgrade(card.key)"
                    />
                  </div>
                </UCard>
              </div>
              <p class="text-xs text-gray-500 dark:text-gray-400">
                {{ t('billing.comparePlansHint') }}
                <NuxtLink to="/pricing" class="underline hover:text-primary-500">
                  {{ t('billing.comparePlansLink') }}
                </NuxtLink>
              </p>
            </template>

            <template v-else>
              <UCard class="card-elevated">
                <div class="flex items-start justify-between gap-4 flex-wrap">
                  <div class="space-y-1">
                    <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                      {{ t('billing.manageHeading') }}
                    </h2>
                    <p class="text-sm text-gray-500 dark:text-gray-400 max-w-xl">
                      {{ t('billing.manageDescription') }}
                    </p>
                  </div>
                  <UButton
                    color="primary"
                    icon="i-heroicons-credit-card"
                    :loading="portalLoading"
                    :label="t('billing.manageCta')"
                    class="btn-interactive"
                    @click="handleManageSubscription"
                  />
                </div>
              </UCard>
            </template>
          </section>

          <!-- Usage -->
          <UCard class="card-elevated">
            <div class="space-y-4">
              <div class="space-y-1">
                <h2 class="font-serif-display text-2xl text-gray-900 dark:text-gray-50">
                  {{ t('billing.usageHeading') }}
                </h2>
                <p class="text-sm text-gray-500 dark:text-gray-400">
                  {{ t('billing.usageDescription') }}
                </p>
              </div>

              <div v-if="usageLoading && !usage" class="skeleton-shimmer h-10" />
              <div v-else class="space-y-3">
                <div class="flex items-baseline justify-between gap-2 flex-wrap">
                  <span class="text-sm text-gray-600 dark:text-gray-400">
                    {{
                      windowCap > 0
                        ? t('billing.usageOfCap', { used: usageCalls, cap: windowCap })
                        : t('billing.usageNoCap', { used: usageCalls })
                    }}
                  </span>
                  <span v-if="windowCap > 0" class="text-sm font-medium text-gray-700 dark:text-gray-300">
                    {{ usagePercent }}%
                  </span>
                </div>
                <div
                  v-if="windowCap > 0"
                  class="h-2 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700"
                  role="progressbar"
                  :aria-valuenow="usagePercent"
                  aria-valuemin="0"
                  aria-valuemax="100"
                >
                  <div
                    class="h-full rounded-full bg-primary-500 transition-[width] duration-300"
                    :style="{ width: usagePercent + '%' }"
                  />
                </div>
                <p class="text-xs text-gray-500 dark:text-gray-400">
                  {{ t('billing.usageWindowNote') }}
                </p>
              </div>
            </div>
          </UCard>
        </template>
        </UContainer>
      </template>
    </UDashboardPanel>
  </div>
</template>
