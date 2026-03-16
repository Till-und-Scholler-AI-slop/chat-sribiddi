<script setup lang="ts">
const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<(e: 'toggle') => void>()

const router = useRouter()
const route = useRoute()
const toast = useToast()

const colorMode = useColorMode()

const isDark = computed({
  get: () => colorMode.value === 'dark',
  set: (value: boolean) => {
    const next = value ? 'dark' : 'light'
    colorMode.preference = next
  }
})

const toggleColorMode = () => {
  isDark.value = !isDark.value
}
const { ensureAuthenticated } = useAuth()
const {
  conversations,
  activeChatId,
  selectChat,
  startChat,
  loadChatsFromBackend,
  renameChat,
  removeChat
} = useChatStore()

const editingChatId = ref<string | null>(null)
const titleDraft = ref('')
const processingChatId = ref<string | null>(null)

onMounted(async () => {
  try {
    const ok = await ensureAuthenticated()
    if (!ok) return
    await loadChatsFromBackend()
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Chats konnten nicht aktualisiert werden',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  }
})

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

const startEditing = (chatId: string, currentTitle?: string | null) => {
  editingChatId.value = chatId
  titleDraft.value = (currentTitle || '').trim() || 'New chat'
}

const cancelEditing = () => {
  editingChatId.value = null
  titleDraft.value = ''
}

const saveTitle = async (chatId: string) => {
  const title = titleDraft.value.trim()
  if (!title) {
    toast.add({
      title: 'Titel fehlt',
      description: 'Bitte gib einen Chat-Titel ein.',
      color: 'warning'
    })
    return
  }

  processingChatId.value = chatId
  try {
    await renameChat(chatId, title)
    toast.add({
      title: 'Chat umbenannt',
      color: 'primary'
    })
    cancelEditing()
  } catch (err) {
    toast.add({
      title: 'Chat konnte nicht umbenannt werden',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    processingChatId.value = null
  }
}

const handleDelete = async (chatId: string) => {
  if (!window.confirm('Diesen Chat wirklich löschen?')) return

  processingChatId.value = chatId
  try {
    await removeChat(chatId)
    toast.add({
      title: 'Chat gelöscht',
      color: 'neutral'
    })
  } catch (err) {
    toast.add({
      title: 'Chat konnte nicht gelöscht werden',
      description: err instanceof Error ? err.message : 'Backend not reachable.',
      color: 'error'
    })
  } finally {
    if (editingChatId.value === chatId) {
      cancelEditing()
    }
    processingChatId.value = null
  }
}

const goToSettings = () => {
  router.push('/settings')
}

const goToModels = () => {
  router.push('/models')
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

    <!-- List -->
    <nav class="flex-1 overflow-auto px-2 pb-2">
      <ul class="space-y-1">
        <li
          v-for="conversation in conversations"
          :key="conversation.session.chatId"
        >
          <div
            class="rounded-lg transition"
            :class="activeChatId === conversation.session.chatId ? 'bg-gray-100 dark:bg-gray-900' : 'hover:bg-gray-100 dark:hover:bg-gray-900'"
          >
            <div
              v-if="editingChatId === conversation.session.chatId && !collapsed"
              class="p-2 space-y-2"
            >
              <UInput
                v-model="titleDraft"
                autofocus
                @keydown.enter.prevent="saveTitle(conversation.session.chatId)"
                @keydown.esc.prevent="cancelEditing"
              />
              <div class="flex items-center gap-2">
                <UButton
                  size="xs"
                  color="primary"
                  label="Speichern"
                  :loading="processingChatId === conversation.session.chatId"
                  @click="saveTitle(conversation.session.chatId)"
                />
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  label="Abbrechen"
                  :disabled="processingChatId === conversation.session.chatId"
                  @click="cancelEditing"
                />
              </div>
            </div>

            <div v-else class="flex items-center gap-1 p-1">
              <button
                class="flex-1 rounded-md text-left px-2 py-2 flex items-center gap-2 min-w-0"
                @click="handleSelect(conversation.session.chatId)"
              >
                <span v-if="!collapsed" class="truncate">
                  {{ conversation.session.title || 'New chat' }}
                </span>
              </button>

              <div v-if="!collapsed" class="flex items-center gap-1 pr-1">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-heroicons-pencil-square"
                  aria-label="Chat umbenennen"
                  :disabled="processingChatId === conversation.session.chatId"
                  @click.stop="startEditing(conversation.session.chatId, conversation.session.title)"
                />
                <UButton
                  size="xs"
                  variant="ghost"
                  color="error"
                  icon="i-heroicons-trash"
                  aria-label="Chat löschen"
                  :loading="processingChatId === conversation.session.chatId"
                  @click.stop="handleDelete(conversation.session.chatId)"
                />
              </div>
            </div>
          </div>
        </li>
      </ul>
    </nav>

    <!-- Footer -->
    <div class="border-t border-gray-200 dark:border-gray-800 p-2 space-y-2">
      <UButton
        variant="ghost"
        :icon="isDark ? 'i-heroicons-sun' : 'i-heroicons-moon'"
        :label="collapsed ? '' : isDark ? 'Light Mode' : 'Dark Mode'"
        :aria-label="isDark ? 'Switch to Light Mode' : 'Switch to Dark Mode'"
        class="w-full justify-start"
        @click="toggleColorMode"
      />
      <UButton
        variant="ghost"
        icon="i-heroicons-cpu-chip"
        :label="collapsed ? '' : 'Modelle'"
        class="w-full justify-start"
        @click="goToModels"
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
