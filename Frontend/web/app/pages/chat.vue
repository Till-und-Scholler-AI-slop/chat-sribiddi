<script setup lang="ts">
import type { MediaAttachment, ModelOption } from '~/composables/useChatApi'

definePageMeta({
  layout: 'default'
})

const {
  activeConversation,
  activeChatId,
  conversations,
  defaultModel,
  ensureModels,
  loadChatsFromBackend,
  models,
  modelsError,
  modelsLoading,
  retryLastPrompt,
  sendPrompt,
  setModelForChat,
  startChat,
  stop
} = useChatStore()

const input = ref('')
const attachments = ref<MediaAttachment[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const initializing = ref(true)
const toast = useToast()

const selectedModel = computed<ModelOption | null>({
  get: () => {
    const conversation = activeConversation.value
    if (conversation?.model) return conversation.model
    return defaultModel.value
  },
  set: value => {
    if (value && activeConversation.value) {
      setModelForChat(activeConversation.value.session.chatId, value)
    }
  }
})

const status = computed(() => activeConversation.value?.status || 'ready')
const errorMessage = computed(() => activeConversation.value?.error || modelsError.value)

const firstText = (parts?: Array<{ type: string; text?: string }>) =>
  parts?.find(part => part.type === 'text' && typeof part.text === 'string')?.text || ''

const toDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(new Error(`Unable to read ${file.name}`))
    reader.readAsDataURL(file)
  })

const clearAttachments = () => {
  attachments.value = []
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

const removeAttachment = (index: number) => {
  attachments.value.splice(index, 1)
}

const handleFiles = async (event: Event) => {
  const inputNode = event.target as HTMLInputElement
  const files = Array.from(inputNode.files || [])
  if (files.length === 0) return

  try {
    const loaded = await Promise.all(
      files.map(async file => ({
        name: file.name,
        mimeType: file.type || 'application/octet-stream',
        data: await toDataUrl(file)
      }))
    )

    attachments.value.push(...loaded)
  } catch (err) {
    toast.add({
      title: 'Upload failed',
      description: err instanceof Error ? err.message : 'Could not read attachment.',
      color: 'error'
    })
  } finally {
    inputNode.value = ''
  }
}

onMounted(async () => {
  try {
    await ensureModels()
    await loadChatsFromBackend()
    if (conversations.value.length === 0) {
      await startChat('New chat')
    }

    if (activeConversation.value && !activeConversation.value.model && defaultModel.value) {
      setModelForChat(activeConversation.value.session.chatId, defaultModel.value)
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Chat could not start',
      description: err instanceof Error ? err.message : 'Check if the backend is running.',
      color: 'error'
    })
  } finally {
    initializing.value = false
  }
})

watch(activeChatId, newId => {
  const conversation = conversations.value.find(c => c.session.chatId === newId)
  if (conversation && !conversation.model && defaultModel.value) {
    setModelForChat(conversation.session.chatId, defaultModel.value)
  }
})

async function handleSubmit() {
  if (!input.value.trim() && attachments.value.length === 0) return
  if (!activeConversation.value) {
    await startChat('New chat')
  }

  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  const text = input.value.trim() || 'Please analyze the attachment.'
  const media = [...attachments.value]
  input.value = ''
  clearAttachments()

  try {
    await sendPrompt(conversation.session.chatId, text, model, {
      media
    })
  } catch (err) {
    toast.add({
      title: 'Message failed',
      description: err instanceof Error ? err.message : 'Unable to reach backend.',
      color: 'error'
    })
  }
}

const handleStop = () => {
  if (activeConversation.value) {
    stop(activeConversation.value.session.chatId)
  }
}

const handleReload = async () => {
  const conversation = activeConversation.value
  const model = selectedModel.value
  if (!conversation || !model) return

  try {
    await retryLastPrompt(conversation.session.chatId, model)
  } catch (err) {
    toast.add({
      title: 'Retry failed',
      description: err instanceof Error ? err.message : 'Unable to retry request.',
      color: 'error'
    })
  }
}
</script>

<template>
  <UDashboardPanel class="h-full flex flex-col">
    <template #header>
      <UDashboardNavbar
        title="Chat"
        :badge="modelsLoading ? 'Loading models…' : undefined"
      />
    </template>

    <template #body>
      <UContainer class="py-4 space-y-4 flex-1 overflow-hidden flex flex-col">
        <UAlert
          v-if="errorMessage"
          color="error"
          icon="i-heroicons-exclamation-triangle"
          :description="errorMessage"
        />

        <div v-if="initializing" class="space-y-3">
          <USkeleton class="h-14" />
          <USkeleton class="h-24" />
          <USkeleton class="h-14" />
        </div>

        <div v-else-if="!activeConversation">
          <UEmptyState
            icon="i-heroicons-chat-bubble-left-right"
            title="No chat yet"
            description="Start a new chat to talk to the backend models."
          >
            <UButton color="primary" icon="i-heroicons-plus" @click="startChat('New chat')">
              Start chat
            </UButton>
          </UEmptyState>
        </div>

        <div v-else class="space-y-4 flex-1 min-h-0 flex flex-col">
          <div class="flex items-center justify-between gap-2">
            <div class="text-sm text-gray-500 dark:text-gray-400">
              Chat ID: {{ activeConversation.session.chatId }}
            </div>
            <UBadge
              v-if="selectedModel"
              color="primary"
              variant="soft"
              :label="selectedModel.label"
              :ui="{ rounded: 'rounded-full' }"
            />
          </div>

          <div class="flex-1 min-h-0">
            <UCard class="h-full" :ui="{ body: 'h-full overflow-hidden' }">
              <div class="h-full overflow-y-auto pr-1">
                <UChatMessages
                  class="h-full"
                  :messages="activeConversation.messages"
                  :status="status"
                  :should-scroll-to-bottom="true"
                  :auto-scroll="{ size: 'sm', color: 'neutral', variant: 'outline' }"
                  :spacing-offset="96"
                >
                  <template #content="{ parts }">
                    <MarkdownMessage :text="firstText(parts)" />
                  </template>
                </UChatMessages>
              </div>
            </UCard>
          </div>
        </div>
      </UContainer>
    </template>

    <template #footer>
      <UContainer class="pb-4 sm:pb-6 space-y-2">
        <div class="flex items-center gap-2 flex-wrap">
          <label class="inline-flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300">
            <UIcon name="i-heroicons-photo" />
            <span>Add attachments</span>
            <input
              ref="fileInput"
              type="file"
              class="hidden"
              accept="image/*"
              multiple
              @change="handleFiles"
            >
          </label>
          <UButton
            size="xs"
            variant="ghost"
            color="neutral"
            icon="i-heroicons-paper-clip"
            label="Choose files"
            @click="fileInput?.click()"
          />
          <UButton
            v-if="attachments.length"
            size="xs"
            variant="ghost"
            color="neutral"
            icon="i-heroicons-x-mark"
            label="Clear"
            @click="clearAttachments"
          />
        </div>

        <div v-if="attachments.length" class="flex flex-wrap gap-2">
          <UBadge
            v-for="(item, index) in attachments"
            :key="`${item.name || item.mimeType}-${index}`"
            variant="soft"
            color="neutral"
          >
            <span class="mr-1">{{ item.name || item.mimeType }}</span>
            <button class="inline-flex" @click="removeAttachment(index)">
              <UIcon name="i-heroicons-x-mark" class="size-3" />
            </button>
          </UBadge>
        </div>

        <UChatPrompt v-model="input" :error="errorMessage" @submit="handleSubmit">
          <template #header>
            <USelectMenu
              v-model="selectedModel"
              :items="models"
              class="w-56"
              :loading="modelsLoading"
              option-attribute="label"
              value-attribute="value"
              by="value"
              placeholder="Pick model"
              variant="subtle"
              color="neutral"
            >
              <template #leading>
                <UIcon name="i-heroicons-cpu-chip" />
              </template>
              <template #option="{ option }">
                <div class="flex items-center justify-between">
                  <span>{{ option.label }}</span>
                  <UBadge :label="option.provider" variant="soft" color="neutral" />
                </div>
              </template>
            </USelectMenu>
          </template>

          <UChatPromptSubmit
            :status="status"
            @stop="handleStop"
            @reload="handleReload"
          />
        </UChatPrompt>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
