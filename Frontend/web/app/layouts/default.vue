<script setup lang="ts">
const route = useRoute()

const isCollapsed = ref(false)
const isMobile = ref(false)
const sidebarOpen = ref(false)

function toggleSidebar() {
  if (isMobile.value) {
    sidebarOpen.value = !sidebarOpen.value
  } else {
    isCollapsed.value = !isCollapsed.value
  }
}

function closeSidebar() {
  if (isMobile.value) {
    sidebarOpen.value = false
  }
}

onMounted(() => {
  const mediaQuery = window.matchMedia('(max-width: 768px)')
  isMobile.value = mediaQuery.matches
  
  const handler = (e: MediaQueryListEvent) => {
    isMobile.value = e.matches
    if (!e.matches) {
      sidebarOpen.value = false
    }
  }
  
  mediaQuery.addEventListener('change', handler)
  onUnmounted(() => mediaQuery.removeEventListener('change', handler))
})

watch(() => route.path, () => {
  if (isMobile.value) {
    sidebarOpen.value = false
  }
})
</script>

<template>
  <div class="h-dvh w-dvw overflow-hidden bg-chat-gradient">
    <div 
      class="grid h-full min-h-0 transition-[grid-template-columns] duration-200 ease-out" 
      :class="[
        isMobile ? 'grid-cols-[1fr]' : (isCollapsed ? 'grid-cols-[56px_1fr]' : 'grid-cols-[280px_1fr]')
      ]"
    >
      <!-- Mobile Backdrop -->
      <div
        v-if="isMobile && sidebarOpen"
        class="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm transition-opacity"
        @click="closeSidebar"
      />

      <!-- Sidebar -->
      <aside 
        class="h-full min-h-0 overflow-hidden glass-surface border-r border-gray-200/60 dark:border-white/[0.06] transition-transform duration-200 ease-out"
        :class="[
          isMobile ? 'fixed inset-y-0 left-0 z-50 w-[280px]' : '',
          isMobile && !sidebarOpen ? '-translate-x-full' : 'translate-x-0'
        ]"
      >
        <ChatSidebar :collapsed="isMobile ? false : isCollapsed" @toggle="toggleSidebar" @close="closeSidebar" />
      </aside>

      <!-- Main -->
      <main class="h-full min-h-0 overflow-auto scrollbar-thin">
        <!-- Mobile Header Bar -->
        <div
          v-if="isMobile"
          class="sticky top-0 z-30 flex items-center gap-3 px-3 py-2 glass-surface border-b border-gray-200/60 dark:border-white/[0.06]"
        >
          <UButton
            variant="ghost"
            icon="i-heroicons-bars-3"
            aria-label="Menu"
            class="btn-interactive"
            @click="toggleSidebar"
          />
        </div>
        <slot />
      </main>
    </div>
  </div>
</template>
