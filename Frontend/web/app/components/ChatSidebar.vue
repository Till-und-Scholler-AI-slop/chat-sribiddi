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

// Delete-confirmation modal state.
const deleteTarget = ref<{ chatId: string; title: string } | null>(null)
const deleteModalOpen = computed({
  get: () => deleteTarget.value !== null,
  set: value => {
    if (!value) deleteTarget.value = null
  }
})

onMounted(async () => {
  try {
    const ok = await ensureAuthenticated()
    if (!ok) return
    await loadChatsFromBackend()
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Chats konnten nicht geladen werden',
      description: err instanceof Error ? err.message : 'Backend nicht erreichbar.',
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

    const session = await startChat()
    selectChat(session.chatId)
    if (route.path !== '/chat') {
      router.push('/chat')
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: 'Chat konnte nicht erstellt werden',
      description: err instanceof Error ? err.message : 'Backend nicht erreichbar.',
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
  titleDraft.value = (currentTitle || '').trim() || 'Neuer Chat'
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
      description: err instanceof Error ? err.message : 'Backend nicht erreichbar.',
      color: 'error'
    })
  } finally {
    processingChatId.value = null
  }
}

const requestDelete = (chatId: string, title?: string | null) => {
  deleteTarget.value = {
    chatId,
    title: (title || '').trim() || 'Neuer Chat'
  }
}

const confirmDelete = async () => {
  const target = deleteTarget.value
  if (!target) return
  const { chatId } = target

  processingChatId.value = chatId
  try {
    await removeChat(chatId)
    toast.add({
      title: 'Chat gelöscht',
      color: 'neutral'
    })
    deleteTarget.value = null
  } catch (err) {
    toast.add({
      title: 'Chat konnte nicht gelöscht werden',
      description: err instanceof Error ? err.message : 'Backend nicht erreichbar.',
      color: 'error'
    })
  } finally {
    if (editingChatId.value === chatId) {
      cancelEditing()
    }
    processingChatId.value = null
  }
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
          class="btn-interactive"
          @click="emit('toggle')"
      />
      <div v-if="!collapsed" class="flex-1 font-semibold truncate tracking-tight">
        Chats
      </div>
      <UButton
          v-if="!collapsed"
          color="primary"
          variant="soft"
          icon="i-heroicons-plus"
          label="Neu"
          class="btn-interactive"
          @click="handleNewChat"
      />
    </div>

    <!-- List -->
    <nav class="flex-1 overflow-auto px-2 pb-2 scrollbar-thin">
      <ul class="space-y-1">
        <li
          v-for="conversation in conversations"
          :key="conversation.session.chatId"
        >
          <div
            class="rounded-lg transition-all duration-150"
            :class="activeChatId === conversation.session.chatId ? 'chat-item-active' : 'chat-item-hover hover:bg-gray-100/60 dark:hover:bg-white/[0.04]'"
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
                  class="btn-interactive"
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

            <div v-else class="group flex items-center gap-1 p-1">
              <button
                class="flex-1 rounded-md text-left px-2 py-2 flex items-center gap-2 min-w-0"
                @click="handleSelect(conversation.session.chatId)"
              >
                <span v-if="!collapsed" class="truncate text-sm">
                  {{ conversation.session.title || 'Neuer Chat' }}
                </span>
              </button>

              <div v-if="!collapsed" class="flex items-center gap-1 pr-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-heroicons-pencil-square"
                  aria-label="Chat umbenennen"
                  class="btn-interactive"
                  :disabled="processingChatId === conversation.session.chatId"
                  @click.stop="startEditing(conversation.session.chatId, conversation.session.title)"
                />
                <UButton
                  size="xs"
                  variant="ghost"
                  color="error"
                  icon="i-heroicons-trash"
                  aria-label="Chat löschen"
                  class="btn-interactive"
                  :loading="processingChatId === conversation.session.chatId"
                  @click.stop="requestDelete(conversation.session.chatId, conversation.session.title)"
                />
              </div>
            </div>
          </div>
        </li>
      </ul>
    </nav>

    <!-- Footer -->
    <div class="border-t border-gray-200/60 dark:border-white/[0.06] p-2 space-y-1">
      <UButton
        variant="ghost"
        :icon="isDark ? 'i-heroicons-sun' : 'i-heroicons-moon'"
        :label="collapsed ? '' : isDark ? 'Heller Modus' : 'Dunkler Modus'"
        :aria-label="isDark ? 'Zum hellen Modus wechseln' : 'Zum dunklen Modus wechseln'"
        class="w-full justify-start btn-interactive"
        @click="toggleColorMode"
      />
      <UButton
        variant="ghost"
        icon="i-heroicons-cpu-chip"
        :label="collapsed ? '' : 'Modelle'"
        to="/models"
        class="w-full justify-start btn-interactive"
      />
      <UButton
        variant="ghost"
        icon="i-heroicons-cog-6-tooth"
        :label="collapsed ? '' : 'Einstellungen'"
        to="/settings"
        class="w-full justify-start btn-interactive"
      />
    </div>

    <!-- Delete confirmation -->
    <UModal v-model:open="deleteModalOpen" :ui="{ width: 'max-w-md' }">
      <template #content>
        <div class="p-5 space-y-4">
          <div class="flex items-start gap-3">
            <div class="p-2 rounded-lg bg-red-100 text-red-600 dark:bg-red-500/15 dark:text-red-400">
              <UIcon name="i-heroicons-trash" class="size-5" />
            </div>
            <div class="flex-1 min-w-0">
              <h3 class="text-base font-semibold tracking-tight">Chat löschen?</h3>
              <p class="text-sm text-gray-600 dark:text-gray-400 mt-1">
                „<span class="font-medium">{{ deleteTarget?.title }}</span>" wird unwiderruflich entfernt – inklusive aller Nachrichten.
              </p>
            </div>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <UButton
              variant="ghost"
              color="neutral"
              label="Abbrechen"
              :disabled="processingChatId === deleteTarget?.chatId"
              @click="deleteTarget = null"
            />
            <UButton
              color="error"
              icon="i-heroicons-trash"
              label="Endgültig löschen"
              :loading="processingChatId === deleteTarget?.chatId"
              @click="confirmDelete"
            />
          </div>
        </div>
      </template>
    </UModal>
  </div>
</template>
