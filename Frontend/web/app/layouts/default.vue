<script setup lang="ts">
const isCollapsed = ref(false)

function toggleSidebar() {
  isCollapsed.value = !isCollapsed.value
}
</script>

<template>
  <div class="h-dvh w-dvw overflow-hidden">
    <div class="grid h-full transition-[grid-template-columns] duration-200 ease-out" :class="isCollapsed ? 'grid-cols-[56px_1fr]' : 'grid-cols-[280px_1fr]'">
      <!-- Sidebar -->
      <aside class="border-r border-gray-200 dark:border-gray-800">
        <ChatSidebar :collapsed="isCollapsed" @toggle="toggleSidebar" />
      </aside>

      <!-- Main -->
      <main class="h-full overflow-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
