<script setup lang="ts">
const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<(e: 'toggle') => void>()

// Dummy-Chats (später ersetzt du das durch API/Store)
const chats = ref([
  { id: 'c1', title: 'DHBW — Theoretische Informatik' },
  { id: 'c2', title: 'Nom-Nom-Now Ideen' },
  { id: 'c3', title: 'Docker Rootless Debug' }
])

const selectedId = ref('c1')
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center gap-2 p-2">
      <UButton
          variant="ghost"
          icon="i-heroicons-bars-3"
          :aria-label="collapsed ? 'Sidebar öffnen' : 'Sidebar schließen'"
          @click="emit('toggle')"
      />
      <div v-if="!collapsed" class="flex-1 font-semibold truncate">
        Chats
      </div>
      <UButton
          v-if="!collapsed"
          color="primary"
          variant="soft"
          icon="i-heroicons-plus"
          label="Neu"
          @click="chats.unshift({ id: crypto.randomUUID(), title: 'Neuer Chat' })"
      />
    </div>

    <!-- Search -->
    <div v-if="!collapsed" class="px-2 pb-2">
      <UInput icon="i-heroicons-magnifying-glass" placeholder="Suchen…" />
    </div>

    <!-- List -->
    <nav class="flex-1 overflow-auto px-2 pb-2">
      <ul class="space-y-1">
        <li v-for="c in chats" :key="c.id">
          <button
              class="w-full rounded-lg text-left px-2 py-2 hover:bg-gray-100 dark:hover:bg-gray-900 transition flex items-center gap-2"
              :class="selectedId === c.id ? 'bg-gray-100 dark:bg-gray-900' : ''"
              @click="selectedId = c.id"
          >
            <span v-if="!collapsed" class="truncate">
              {{ c.title }}
            </span>
          </button>
        </li>
      </ul>
    </nav>

    <!-- Footer -->
    <div class="border-t border-gray-200 dark:border-gray-800 p-2">
      <UButton
          variant="ghost"
          icon="i-heroicons-cog-6-tooth"
          :label="collapsed ? '' : 'Einstellungen'"
          class="w-full justify-start"
      />
    </div>
  </div>
</template>
