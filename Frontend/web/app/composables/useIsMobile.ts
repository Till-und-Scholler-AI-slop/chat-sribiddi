const MEDIA_QUERY = '(max-width: 768px)'

let globalQuery: MediaQueryList | null = null

export function useIsMobile() {
  if (import.meta.server) return ref(false)

  if (!globalQuery) {
    globalQuery = window.matchMedia(MEDIA_QUERY)
  }

  const isMobile = ref(globalQuery.matches)

  function onChange(event: MediaQueryListEvent) {
    isMobile.value = event.matches
  }

  onMounted(() => {
    globalQuery!.addEventListener('change', onChange)
  })

  onUnmounted(() => {
    globalQuery!.removeEventListener('change', onChange)
  })

  return isMobile
}
