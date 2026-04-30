<script setup lang="ts">
const isCollapsed = ref(false)

function toggleSidebar() {
  isCollapsed.value = !isCollapsed.value
}
</script>

<template>
  <div class="h-dvh w-dvw overflow-hidden bg-chat-gradient">
    <div class="grid h-full min-h-0 transition-[grid-template-columns] duration-200 ease-out" :class="isCollapsed ? 'grid-cols-[56px_1fr]' : 'grid-cols-[280px_1fr]'">
      <!-- Sidebar -->
      <aside class="h-full min-h-0 overflow-hidden glass-surface border-r border-gray-200/60 dark:border-white/[0.06]">
        <ChatSidebar :collapsed="isCollapsed" @toggle="toggleSidebar" />
      </aside>

      <!-- Main -->
      <main class="h-full min-h-0 overflow-auto scrollbar-thin">
        <slot />
      </main>
    </div>
  </div>
</template>
