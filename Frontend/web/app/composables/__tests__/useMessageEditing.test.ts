import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { ModelOption } from '../useChatApi'
import { useMessageEditing } from '../useMessageEditing'

describe('useMessageEditing', () => {
  const model: ModelOption = {
    provider: 'OPENAI',
    value: 'gpt-test',
    label: 'GPT Test'
  }

  beforeEach(() => {
    vi.stubGlobal('ref', ref)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('starts and cancels edit state', () => {
    const editing = useMessageEditing({
      activeConversation: ref(null),
      selectedModel: ref(null),
      editMessage: vi.fn(),
      onEditFailed: vi.fn()
    })

    editing.startEditMessage('msg-1', 'old text')

    expect(editing.editingMessageId.value).toBe('msg-1')
    expect(editing.editingText.value).toBe('old text')

    editing.cancelEditMessage()

    expect(editing.editingMessageId.value).toBeNull()
    expect(editing.editingText.value).toBe('')
  })

  it('submits an edit with the active chat and selected model', async () => {
    const editMessage = vi.fn().mockResolvedValue(null)
    const editing = useMessageEditing({
      activeConversation: ref({ session: { chatId: 'chat-1' } }),
      selectedModel: ref(model),
      editMessage,
      onEditFailed: vi.fn()
    })

    editing.startEditMessage('msg-1', 'old text')
    editing.editingText.value = ' new text '

    await editing.submitEditMessage()

    expect(editMessage).toHaveBeenCalledWith('chat-1', 'msg-1', 'new text', model)
    expect(editing.editingMessageId.value).toBeNull()
    expect(editing.editingText.value).toBe('')
  })

  it('routes edit failures to the caller', async () => {
    const error = new Error('failed')
    const onEditFailed = vi.fn()
    const editing = useMessageEditing({
      activeConversation: ref({ session: { chatId: 'chat-1' } }),
      selectedModel: ref(model),
      editMessage: vi.fn().mockRejectedValue(error),
      onEditFailed
    })

    editing.startEditMessage('msg-1', 'old text')
    editing.editingText.value = 'new text'

    await editing.submitEditMessage()

    expect(onEditFailed).toHaveBeenCalledWith(error)
  })
})
