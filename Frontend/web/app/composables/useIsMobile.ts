const MEDIA_QUERY = '(max-width: 768px)'

let listenerAttached = false

export function useIsMobile() {
  const isMobile = useState<boolean>('viewport-is-mobile', () => false)

  if (import.meta.client) {
    onMounted(() => {
      const mediaQuery = window.matchMedia(MEDIA_QUERY)
      isMobile.value = mediaQuery.matches
      if (!listenerAttached) {
        mediaQuery.addEventListener('change', (event: MediaQueryListEvent) => {
          isMobile.value = event.matches
        })
        listenerAttached = true
      }
    })
  }

  return isMobile
}
