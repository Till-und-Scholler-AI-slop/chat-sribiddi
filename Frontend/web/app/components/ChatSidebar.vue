<script setup lang="ts">
const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<(e: 'toggle') => void>()

const router = useRouter()
const route = useRoute()
const toast = useToast()
const { status, ensureAuthenticated } = useAuth()
const {
  conversations,
  activeChatId,
  selectChat,
  startChat
} = useChatStore()

const handleNewChat = async () => {
  try {
    const ok = await ensureAuthenticated()
    if (!ok) {
      router.push('/login')
      return
    }

    const index = conversations.value.length + 1
    const session = await startChat(`Chat ${index}`)
    selectChat(session.chatId)
    if (route.path !== '/chat') {
      router.push('/chat')
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Could not create chat',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  }
}

const handleSelect = (chatId: string) => {
  selectChat(chatId)
  if (route.path !== '/chat') {
    router.push('/chat')
  }
}

const goToSettings = () => {
  router.push('/settings')
}

const goToLogin = () => {
  router.push('/login')
}
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
          @click="handleNewChat"
      />
    </div>

    <!-- Search -->
    <div v-if="!collapsed" class="px-2 pb-2">
      <UInput icon="i-heroicons-magnifying-glass" placeholder="Search (not yet wired)" disabled />
    </div>

    <!-- List -->
    <nav class="flex-1 overflow-auto px-2 pb-2">
      <ul class="space-y-1">
        <li
          v-for="conversation in conversations"
          :key="conversation.session.chatId"
        >
          <button
            class="w-full rounded-lg text-left px-2 py-2 hover:bg-gray-100 dark:hover:bg-gray-900 transition flex items-center gap-2"
            :class="activeChatId === conversation.session.chatId ? 'bg-gray-100 dark:bg-gray-900' : ''"
            @click="handleSelect(conversation.session.chatId)"
          >
            <span v-if="!collapsed" class="truncate">
              {{ conversation.session.title || 'New chat' }}
            </span>
          </button>
        </li>
      </ul>
    </nav>

    <!-- Footer -->
    <div class="border-t border-gray-200 dark:border-gray-800 p-2 space-y-2">
      <UButton
        variant="ghost"
        icon="i-heroicons-arrow-right-on-rectangle"
        :label="collapsed ? '' : 'Login'"
        class="w-full justify-start"
        @click="goToLogin"
      />
      <UButton
        variant="ghost"
        icon="i-heroicons-cog-6-tooth"
        :label="collapsed ? '' : 'Einstellungen'"
        class="w-full justify-start"
        @click="goToSettings"
      />
    </div>
  </div>
</template>
