export function useChatScroll() {
  const messagesContainer = ref<HTMLElement | null>(null)
  const skipNextSmoothScroll = ref(false)
  let streamingRafId: number | null = null

  const scrollToBottom = (behavior: 'smooth' | 'auto' = 'smooth') => {
    nextTick(() => {
      const el = messagesContainer.value
      if (el) {
        el.scrollTo({ top: el.scrollHeight, behavior })
      }
    })
  }

  const skipNextSmooth = () => {
    skipNextSmoothScroll.value = true
  }

  const scrollAfterMessageLengthChange = (newLength: number | undefined) => {
    if (!newLength) return
    if (skipNextSmoothScroll.value) {
      skipNextSmoothScroll.value = false
      return
    }
    scrollToBottom('smooth')
  }

  const scheduleStreamingScroll = (newText: string | undefined) => {
    if (!newText) return
    if (streamingRafId !== null) return
    streamingRafId = requestAnimationFrame(() => {
      streamingRafId = null
      scrollToBottom('auto')
    })
  }

  onBeforeUnmount(() => {
    if (streamingRafId !== null) {
      cancelAnimationFrame(streamingRafId)
      streamingRafId = null
    }
  })

  return {
    messagesContainer,
    scrollToBottom,
    skipNextSmooth,
    scrollAfterMessageLengthChange,
    scheduleStreamingScroll
  }
}
