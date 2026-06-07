<script setup lang="ts">
import type { ComparePanelState } from '~/composables/useCompareStore'
import type { ModelOption } from '~/composables/useChatApi'
import { calculateMessageCost, formatCost } from '~/composables/chat-helpers'
import { formatRatePerMillion, formatTokenCount, PROVIDER_LABELS } from '~/utils/format'

const props = defineProps<{
  panel: ComparePanelState
  models: ModelOption[]
  modelsLoading: boolean
  canRemove: boolean
}>()

const emit = defineEmits<{
  setModel: [panelId: string, model: ModelOption]
  keep: [panelId: string]
  discard: [panelId: string]
  remove: [panelId: string]
}>()

const { t } = useI18n()

const selectedModel = computed<ModelOption | null>({
  get: () => props.panel.model,
  set: value => {
    if (value) {
      emit('setModel', props.panel.id, value)
    }
  }
})

const isStreaming = computed(() => props.panel.status === 'streaming')

const statusLabel = computed(() => {
  if (props.panel.status === 'streaming') return t('compare.statusStreaming')
  if (props.panel.status === 'error') return t('compare.statusError')
  return t('compare.statusReady')
})

const statusColor = computed<'neutral' | 'primary' | 'error'>(() => {
  if (props.panel.status === 'streaming') return 'primary'
  if (props.panel.status === 'error') return 'error'
  return 'neutral'
})

const panelCost = computed(() => calculateMessageCost(props.panel.usage, props.panel.model))

const pricingSummary = computed(() => {
  const model = props.panel.model
  const prompt = formatRatePerMillion(model?.promptPrice)
  const completion = formatRatePerMillion(model?.completionPrice)
  if (prompt && completion) {
    return t('compare.pricingPerMillionInOut', { input: prompt, output: completion })
  }
  if (prompt || completion) {
    return t('compare.pricingPerMillion', { price: prompt || completion })
  }
  return null
})

const lastAssistantMessage = computed(() =>
  [...props.panel.messages].reverse().find(message => message.role === 'assistant') || null
)

const showTyping = computed(() =>
  isStreaming.value && lastAssistantMessage.value?.content.trim() === ''
)

const canKeep = computed(() =>
  Boolean(props.panel.chatId && !props.panel.kept && props.panel.messages.length > 0)
)

const canDiscard = computed(() =>
  !props.panel.kept && Boolean(props.panel.chatId || props.panel.messages.length > 0 || props.panel.error)
)
</script>

<template>
  <section class="compare-panel">
    <header class="compare-panel__header">
      <div class="min-w-0 flex-1 space-y-2">
        <USelectMenu
          v-model="selectedModel"
          :items="models"
          label-key="label"
          by="value"
          class="w-full"
          :loading="modelsLoading"
          :placeholder="t('compare.modelPlaceholder')"
          variant="subtle"
          color="neutral"
          :aria-label="t('compare.modelPlaceholder')"
        >
          <template #leading>
            <UIcon name="i-heroicons-cpu-chip" />
          </template>
          <template #option="{ option }">
            <ChatModelSelectOption :option="option" />
          </template>
        </USelectMenu>

        <div class="flex min-w-0 items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
          <span v-if="panel.model" class="truncate">
            {{ PROVIDER_LABELS[panel.model.provider] }} · {{ panel.model.label }}
          </span>
          <span v-if="pricingSummary" class="inline-flex shrink-0 items-center gap-1">
            <UIcon name="i-heroicons-banknotes" class="size-3 text-emerald-500" />
            {{ pricingSummary }}
          </span>
        </div>
      </div>

      <div class="flex items-start gap-1">
        <UBadge
          :color="statusColor"
          variant="subtle"
          :label="statusLabel"
        />
        <UTooltip v-if="canRemove" :text="t('compare.removePanel')">
          <UButton
            icon="i-heroicons-x-mark"
            color="neutral"
            variant="ghost"
            size="xs"
            :aria-label="t('compare.removePanel')"
            @click="emit('remove', panel.id)"
          />
        </UTooltip>
      </div>
    </header>

    <div class="compare-panel__body scrollbar-thin">
      <div
        v-if="panel.messages.length === 0"
        class="compare-panel__empty"
      >
        <UIcon name="i-heroicons-arrows-right-left" class="size-7 text-gray-300 dark:text-gray-600" />
        <span>{{ t('compare.panelEmpty') }}</span>
      </div>

      <div
        v-for="message in panel.messages"
        :key="message.id"
        class="compare-message"
        :class="message.role === 'user' ? 'compare-message--user' : 'compare-message--assistant'"
      >
        <div class="compare-message__label">
          {{ message.role === 'user' ? t('compare.userMessage') : t('compare.assistantMessage') }}
        </div>
        <div class="compare-message__bubble">
          <MarkdownMessage
            v-if="message.role === 'assistant'"
            :text="message.content"
            :streaming="isStreaming && lastAssistantMessage?.id === message.id"
          />
          <p v-else class="whitespace-pre-wrap">
            {{ message.content }}
          </p>
          <div
            v-if="message.role === 'assistant' && showTyping && lastAssistantMessage?.id === message.id"
            class="typing-dots mt-2"
          >
            <span /><span /><span />
          </div>
        </div>
      </div>
    </div>

    <UAlert
      v-if="panel.error"
      color="error"
      variant="subtle"
      icon="i-heroicons-exclamation-triangle"
      :description="panel.error"
      class="mx-3 mb-3"
    />

    <footer class="compare-panel__footer">
      <dl class="compare-stats">
        <div>
          <dt>{{ t('compare.promptTokens') }}</dt>
          <dd>{{ formatTokenCount(panel.usage?.promptTokens) }}</dd>
        </div>
        <div>
          <dt>{{ t('compare.outputTokens') }}</dt>
          <dd>{{ formatTokenCount(panel.usage?.generationTokens) }}</dd>
        </div>
        <div>
          <dt>{{ t('compare.totalTokens') }}</dt>
          <dd>{{ formatTokenCount(panel.usage?.totalTokens) }}</dd>
        </div>
        <div>
          <dt>{{ t('compare.cost') }}</dt>
          <dd>{{ panelCost ? formatCost(panelCost.totalCost) : '—' }}</dd>
        </div>
      </dl>

      <div class="flex items-center justify-end gap-2">
        <UBadge
          v-if="panel.kept"
          color="primary"
          variant="subtle"
          :label="t('compare.kept')"
        />
        <UButton
          v-if="canDiscard"
          color="neutral"
          variant="ghost"
          size="sm"
          icon="i-heroicons-trash"
          :label="t('compare.discardChat')"
          :disabled="isStreaming"
          @click="emit('discard', panel.id)"
        />
        <UButton
          v-if="canKeep"
          color="primary"
          variant="soft"
          size="sm"
          icon="i-heroicons-arrow-top-right-on-square"
          :label="t('compare.keepChat')"
          :disabled="isStreaming"
          @click="emit('keep', panel.id)"
        />
      </div>
    </footer>
  </section>
</template>

<style scoped>
.compare-panel {
  display: flex;
  min-height: 32rem;
  height: 100%;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--hair);
  border-radius: 0.5rem;
  background: var(--paper-2);
}

.compare-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.75rem;
  border-bottom: 1px solid var(--hair);
}

.compare-panel__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0.75rem;
}

.compare-panel__empty {
  height: 100%;
  min-height: 18rem;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 0.5rem;
  color: var(--ink-2);
  font-size: 0.875rem;
  text-align: center;
}

.compare-panel__footer {
  border-top: 1px solid var(--hair);
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.compare-message {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 0.875rem;
}

.compare-message__label {
  font-size: 0.6875rem;
  line-height: 1rem;
  color: var(--ink-2);
}

.compare-message__bubble {
  border-radius: 0.5rem;
  padding: 0.75rem;
  font-size: 0.875rem;
  line-height: 1.55;
}

.compare-message--user {
  align-items: flex-end;
}

.compare-message--user .compare-message__bubble {
  max-width: 92%;
  background: rgb(var(--ui-primary) / 0.12);
  color: var(--ink);
}

.compare-message--assistant {
  align-items: flex-start;
}

.compare-message--assistant .compare-message__bubble {
  width: 100%;
  background: rgb(255 255 255 / 0.72);
  color: var(--ink);
  border: 1px solid var(--hair);
}

:global(.dark) .compare-message--assistant .compare-message__bubble {
  background: rgb(255 255 255 / 0.04);
}

.compare-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.5rem;
}

.compare-stats div {
  min-width: 0;
}

.compare-stats dt {
  font-size: 0.6875rem;
  line-height: 1rem;
  color: var(--ink-2);
}

.compare-stats dd {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.25rem;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .compare-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .compare-message__bubble {
    font-size: 1rem;
  }

  .compare-panel__header {
    flex-wrap: wrap;
  }
}
</style>
