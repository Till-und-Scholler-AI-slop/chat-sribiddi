<script setup lang="ts">
const route = useRoute()
const { t } = useI18n()
const isMobile = useIsMobile()

const isCollapsed = ref(false)
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

watch(isMobile, (mobile) => {
  if (!mobile) {
    sidebarOpen.value = false
    isCollapsed.value = false
  }
})

watch(() => route.path, () => {
  if (isMobile.value) {
    sidebarOpen.value = false
  }
})

const mobileHeaderTitle = computed(() => {
  const path = route.path
  if (path.startsWith('/chat')) return t('sidebar.chatsTitle')
  if (path.startsWith('/settings')) return t('settings.title')
  if (path.startsWith('/models')) return t('sidebar.models')
  if (path.startsWith('/compare')) return t('sidebar.compare')
  if (path.startsWith('/usage')) return t('sidebar.usage')
  return ''
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
        style="top: env(safe-area-inset-top); bottom: env(safe-area-inset-bottom)"
        @click="closeSidebar"
        @touchmove.prevent
      />

      <!-- Sidebar -->
      <aside 
        class="h-full min-h-0 overflow-hidden glass-surface border-r border-gray-200/60 dark:border-white/[0.06] transition-transform duration-200 ease-out"
        :class="[
          isMobile ? 'fixed inset-y-0 left-0 z-50 w-[280px]' : '',
          isMobile && !sidebarOpen ? '-translate-x-full' : 'translate-x-0'
        ]"
        :style="isMobile ? 'padding-top: env(safe-area-inset-top); padding-bottom: env(safe-area-inset-bottom)' : ''"
      >
        <ChatSidebar :collapsed="isMobile ? false : isCollapsed" @toggle="toggleSidebar" @close="closeSidebar" />
      </aside>

      <!-- Main -->
      <main class="h-full min-h-0 overflow-auto scrollbar-thin">
        <!-- Mobile Header Bar -->
        <div
          v-if="isMobile"
          class="sticky top-0 z-30 flex items-center gap-3 px-3 py-2 glass-surface border-b border-gray-200/60 dark:border-white/[0.06]"
          style="padding-top: max(0.5rem, env(safe-area-inset-top))"
        >
          <UButton
            variant="ghost"
            icon="i-heroicons-bars-3"
            aria-label="Menu"
            class="btn-interactive"
            @click="toggleSidebar"
          />
          <span class="text-sm font-medium text-gray-700 dark:text-gray-300 truncate">
            {{ mobileHeaderTitle }}
          </span>
        </div>
        <slot />
      </main>
    </div>
  </div>
</template>
