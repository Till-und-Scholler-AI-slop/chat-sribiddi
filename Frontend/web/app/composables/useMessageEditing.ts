import type { UIMessage } from 'ai'
import type { ModelOption } from '~/composables/useChatApi'

type ReadableRef<T> = {
  readonly value: T
}

type EditableConversation = {
  session: {
    chatId: string
  }
}

type MessageEditingOptions = {
  activeConversation: ReadableRef<EditableConversation | null>
  selectedModel: ReadableRef<ModelOption | null>
  editMessage: (
    chatId: string,
    messageUiId: string,
    newText: string,
    model: ModelOption
  ) => Promise<UIMessage | null>
  onEditFailed: (error: unknown) => void
}

export function useMessageEditing(options: MessageEditingOptions) {
  const editingMessageId = ref<string | null>(null)
  const editingText = ref('')

  const startEditMessage = (messageId: string, currentText: string) => {
    editingMessageId.value = messageId
    editingText.value = currentText
  }

  const cancelEditMessage = () => {
    editingMessageId.value = null
    editingText.value = ''
  }

  const submitEditMessage = async () => {
    const msgId = editingMessageId.value
    const newText = editingText.value.trim()
    if (!msgId || !newText) return

    const conversation = options.activeConversation.value
    const model = options.selectedModel.value
    if (!conversation || !model) return

    editingMessageId.value = null
    editingText.value = ''

    try {
      await options.editMessage(conversation.session.chatId, msgId, newText, model)
    } catch (err) {
      options.onEditFailed(err)
    }
  }

  return {
    editingMessageId,
    editingText,
    startEditMessage,
    cancelEditMessage,
    submitEditMessage
  }
}
