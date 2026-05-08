<script setup lang="ts">
import type { CSSProperties } from 'vue'
import type { ModelOption } from '~/composables/useChatApi'
import { isModelLocked } from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const { t } = useI18n()
const tt = useTranslatedError()
const toast = useToast()
const router = useRouter()

const {
  panels,
  sharedPrompt,
  modelsLoading,
  modelsError,
  sortedModels,
  isStreaming,
  canAddPanel,
  canRemovePanel,
  ensureModels,
  setPanelModel,
  addPanel,
  removePanel,
  sendComparison,
  cancelAll,
  discardPanelChat,
  clearResults,
  keepPanelChat,
  cleanupUnkeptChats
} = useCompareStore()

const initializing = ref(true)
const clearing = ref(false)

const columnStyle = computed<CSSProperties>(() => ({
  '--compare-panel-count': String(panels.value.length)
}) as CSSProperties)

onMounted(async () => {
  try {
    await ensureModels()
  } catch (error) {
    toast.add({
      title: t('compare.loadFailedTitle'),
      description: tt(error),
      color: 'error'
    })
  } finally {
    initializing.value = false
  }
})

onUnmounted(() => {
  void cleanupUnkeptChats()
})

const handleSetPanelModel = async (panelId: string, model: ModelOption) => {
  if (isModelLocked(model)) {
    await router.push('/pricing')
    return
  }

  await setPanelModel(panelId, model)
}

const handleSend = async () => {
  if (!sharedPrompt.value.trim()) {
    toast.add({
      title: t('compare.missingPromptTitle'),
      description: t('compare.missingPromptDescription'),
      color: 'warning'
    })
    return
  }

  await sendComparison()
}

const handleClear = async () => {
  clearing.value = true
  try {
    await clearResults()
  } finally {
    clearing.value = false
  }
}

const handleKeep = async (panelId: string) => {
  const chatId = keepPanelChat(panelId)
  if (!chatId) return

  toast.add({
    title: t('compare.keepSuccessTitle'),
    color: 'primary'
  })
  await router.push('/chat')
}
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <UDashboardPanel class="h-full min-h-0 flex flex-col">
      <template #body>
        <UContainer class="h-full min-h-0 max-w-none py-4 flex flex-col gap-4 overflow-hidden">
          <header class="compare-header">
            <div class="min-w-0">
              <h1 class="compare-header__title">
                {{ t('compare.title') }}
              </h1>
              <p class="compare-header__subtitle">
                {{ t('compare.subtitle') }}
              </p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <UButton
                icon="i-heroicons-plus"
                color="neutral"
                variant="soft"
                :label="t('compare.addPanel')"
                :disabled="!canAddPanel || isStreaming || initializing"
                @click="addPanel"
              />
              <UButton
                icon="i-heroicons-trash"
                color="neutral"
                variant="ghost"
                :label="t('compare.clearResults')"
                :loading="clearing"
                :disabled="isStreaming || initializing"
                @click="handleClear"
              />
            </div>
          </header>

          <form class="compare-prompt" @submit.prevent="handleSend">
            <textarea
              v-model="sharedPrompt"
              class="compare-prompt__input scrollbar-thin"
              :placeholder="t('compare.promptPlaceholder')"
              :aria-label="t('compare.promptPlaceholder')"
              :disabled="isStreaming || initializing"
              rows="3"
              @keydown.meta.enter.prevent="handleSend"
              @keydown.ctrl.enter.prevent="handleSend"
            />
            <div class="compare-prompt__actions">
              <UButton
                v-if="isStreaming"
                type="button"
                color="error"
                variant="soft"
                icon="i-heroicons-stop"
                :label="t('compare.stopAll')"
                @click="cancelAll"
              />
              <UButton
                v-else
                type="submit"
                color="primary"
                icon="i-heroicons-paper-airplane"
                :label="t('compare.send')"
                :loading="initializing"
                :disabled="sortedModels.length === 0"
              />
            </div>
          </form>

          <UAlert
            v-if="modelsError"
            color="error"
            variant="subtle"
            icon="i-heroicons-exclamation-triangle"
            :title="t('compare.modelLoadFailedTitle')"
            :description="modelsError"
          />

          <div v-if="initializing" class="grid gap-3 md:grid-cols-2">
            <div class="skeleton-shimmer h-[32rem]" />
            <div class="skeleton-shimmer h-[32rem]" />
          </div>

          <div v-else class="compare-scroll scrollbar-thin">
            <div class="compare-columns" :style="columnStyle">
              <ComparePanel
                v-for="panel in panels"
                :key="panel.id"
                :panel="panel"
                :models="sortedModels"
                :models-loading="modelsLoading"
                :can-remove="canRemovePanel"
                @set-model="handleSetPanelModel"
                @keep="handleKeep"
                @discard="discardPanelChat"
                @remove="removePanel"
              />
            </div>
          </div>
        </UContainer>
      </template>
    </UDashboardPanel>
  </div>
</template>

<style scoped>
.compare-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--hair);
  background: var(--paper-2);
}

.compare-header__title {
  font-size: 1.125rem;
  line-height: 1.5rem;
  font-weight: 650;
  color: var(--ink);
}

.compare-header__subtitle {
  margin-top: 0.125rem;
  font-size: 0.8125rem;
  line-height: 1.25rem;
  color: var(--ink-2);
}

.compare-prompt {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 0.75rem;
  padding: 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid var(--hair);
  background: var(--paper-2);
}

.compare-prompt__input {
  width: 100%;
  min-height: 5rem;
  resize: vertical;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: color-mix(in oklab, var(--paper) 92%, var(--ink) 4%);
  padding: 0.75rem;
  color: var(--ink);
  font-size: 0.9375rem;
  line-height: 1.5rem;
  outline: none;
}

.compare-prompt__input::placeholder {
  color: var(--ink-2);
  opacity: 1;
}

.compare-prompt__input:focus {
  border-color: var(--accent-line);
  box-shadow: 0 0 0 3px color-mix(in oklab, var(--accent) 16%, transparent);
}

.compare-prompt__input:disabled {
  cursor: not-allowed;
  opacity: 1;
  background: color-mix(in oklab, var(--paper) 88%, var(--ink) 8%);
  color: var(--ink-2);
  -webkit-text-fill-color: var(--ink-2);
}

:global(.dark) .compare-prompt__input {
  background: color-mix(in oklab, var(--paper-2) 86%, var(--ink) 7%);
}

:global(.dark) .compare-prompt__input:disabled {
  background: color-mix(in oklab, var(--paper-2) 82%, var(--ink) 10%);
}

.compare-prompt__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.compare-scroll {
  flex: 1;
  min-height: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 0.25rem;
}

.compare-columns {
  display: grid;
  min-height: 100%;
  gap: 0.75rem;
  grid-template-columns: 1fr;
}

@media (min-width: 768px) {
  .compare-columns {
    grid-template-columns: repeat(var(--compare-panel-count), minmax(22rem, 1fr));
    min-width: max(
      100%,
      calc((var(--compare-panel-count) * 22rem) + ((var(--compare-panel-count) - 1) * 0.75rem))
    );
  }
}

@media (max-width: 767px) {
  .compare-header,
  .compare-prompt {
    grid-template-columns: 1fr;
  }

  .compare-header {
    align-items: stretch;
    flex-direction: column;
  }

  .compare-prompt__actions {
    justify-content: stretch;
  }
}
</style>
