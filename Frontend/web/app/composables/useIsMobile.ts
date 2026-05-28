const MEDIA_QUERY = '(max-width: 768px)'

let sharedIsMobile: ReturnType<typeof ref<boolean>> | null = null
let listenerAttached = false

export function useIsMobile() {
  if (import.meta.server) return ref(false)

  if (!sharedIsMobile) {
    const mq = window.matchMedia(MEDIA_QUERY)
    sharedIsMobile = ref(mq.matches)
  }

  if (!listenerAttached) {
    const mq = window.matchMedia(MEDIA_QUERY)
    mq.addEventListener('change', (e: MediaQueryListEvent) => {
      if (sharedIsMobile) sharedIsMobile.value = e.matches
    })
    listenerAttached = true
  }

  return sharedIsMobile
}
