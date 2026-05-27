<script setup lang="ts">
defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<(e: 'toggle') => void>()

const router = useRouter()
const route = useRoute()
const toast = useToast()
const { t } = useI18n()
const tt = useTranslatedError()

const { checkAuth, status: authStatus, logout } = useAuth()
const { baseUrl: backendBaseUrl } = useChatApi()
const { plan, ensureLoaded: ensureEntitlementsLoaded } = usePlan()
const { settings } = useUserSettings()
const {
  conversations,
  activeChatId,
  selectChat,
  startChat,
  loadChatsFromBackend,
  loadGuestChats,
  renameChat,
  removeChat,
  hasGuestChats,
  guestChatCount,
  importGuestChats
} = useChatStore()

const editingChatId = ref<string | null>(null)
const titleDraft = ref('')
const processingChatId = ref<string | null>(null)
const colorMode = useColorMode()
const guestImportOpen = ref(false)
const importingGuestChats = ref(false)
// Delete-confirmation modal state.
const deleteTarget = ref<{ chatId: string; title: string } | null>(null)
const deleteModalOpen = computed({
  get: () => deleteTarget.value !== null,
  set: value => {
    if (!value) deleteTarget.value = null
  }
})

const isAuthenticated = computed(() => authStatus.value === 'authenticated')

const loginUrl = computed(() => `${backendBaseUrl.value}/oauth2/authorization/google`)

const themeToggleIcon = computed(() =>
  colorMode.value === 'dark' ? 'i-heroicons-sun' : 'i-heroicons-moon'
)

const themeToggleLabel = computed(() =>
  colorMode.value === 'dark' ? t('sidebar.switchToLightMode') : t('sidebar.switchToDarkMode')
)

const toggleColorMode = () => {
  colorMode.preference = colorMode.value === 'dark' ? 'light' : 'dark'
}

const openLogin = () => {
  if (import.meta.client) {
    window.location.href = loginUrl.value
  }
}

const maybeOpenGuestImportPrompt = () => {
  if (isAuthenticated.value && hasGuestChats() && !settings.value.guestImportDismissed) {
    guestImportOpen.value = true
  }
}

onMounted(async () => {
  try {
    const auth = await checkAuth()
    if (auth === 'authenticated') {
      await Promise.all([
        loadChatsFromBackend(),
        // Load entitlements so the plan badge reflects the current tier.
        ensureEntitlementsLoaded()
      ])
      maybeOpenGuestImportPrompt()
    } else {
      await loadGuestChats()
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: t('sidebar.loadChatsFailed'),
      description: tt(err),
      color: 'error'
    })
  }
})

const handleNewChat = async () => {
  try {
    if (authStatus.value === 'unknown') {
      await checkAuth()
    }
    const session = await startChat()
    selectChat(session.chatId)
    if (route.path !== '/chat') {
      router.push('/chat')
    }
  } catch (err) {
    console.error(err)
    toast.add({
      title: t('sidebar.createChatFailed'),
      description: tt(err),
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
  titleDraft.value = (currentTitle || '').trim() || t('sidebar.newChatPlaceholder')
}

const cancelEditing = () => {
  editingChatId.value = null
  titleDraft.value = ''
}

const saveTitle = async (chatId: string) => {
  const title = titleDraft.value.trim()
  if (!title) {
    toast.add({
      title: t('sidebar.missingTitle'),
      description: t('sidebar.missingTitleDescription'),
      color: 'warning'
    })
    return
  }

  processingChatId.value = chatId
  try {
    await renameChat(chatId, title)
    toast.add({
      title: t('sidebar.renameSuccess'),
      color: 'primary'
    })
    cancelEditing()
  } catch (err) {
    toast.add({
      title: t('sidebar.renameFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    processingChatId.value = null
  }
}

const requestDelete = (chatId: string, title?: string | null) => {
  deleteTarget.value = {
    chatId,
    title: (title || '').trim() || t('sidebar.newChatPlaceholder')
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
      title: t('sidebar.deleteSuccess'),
      color: 'neutral'
    })
    deleteTarget.value = null
  } catch (err) {
    toast.add({
      title: t('sidebar.deleteFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    if (editingChatId.value === chatId) {
      cancelEditing()
    }
    processingChatId.value = null
  }
}

const loggingOut = ref(false)

const handleLogout = async () => {
  if (loggingOut.value) return
  loggingOut.value = true
  const ok = await logout()
  loggingOut.value = false
  toast.add(
    ok
      ? { title: t('auth.logoutSuccess'), color: 'neutral' }
      : {
          title: t('auth.logoutPartialTitle'),
          description: t('auth.logoutPartialDescription'),
          color: 'warning'
        }
  )
  await loadGuestChats()
  await router.push('/chat')
}

const handleSwitchAccount = async () => {
  if (loggingOut.value) return
  loggingOut.value = true
  // Terminate the current backend session first so the subsequent OAuth2
  // flow starts clean. The authorization request already includes
  // `prompt=select_account`, so Google will show the account picker.
  await logout()
  loggingOut.value = false
  if (import.meta.client) {
    window.location.href = loginUrl.value
  }
}

const keepGuestChatsLocal = () => {
  settings.value.guestImportDismissed = true
  guestImportOpen.value = false
}

const dismissGuestImport = () => {
  guestImportOpen.value = false
}

const handleGuestImport = async () => {
  if (importingGuestChats.value) return
  importingGuestChats.value = true
  try {
    await importGuestChats()
    toast.add({
      title: t('sidebar.guestImportSuccess'),
      color: 'primary'
    })
    guestImportOpen.value = false
  } catch (err) {
    toast.add({
      title: t('sidebar.guestImportFailed'),
      description: tt(err),
      color: 'error'
    })
  } finally {
    importingGuestChats.value = false
  }
}

// Plan badge (FE-7): color-coded per tier.
// Free=neutral (gray), Premium=primary (sky blue), Max=secondary (purple).
const planBadgeColor = computed<'neutral' | 'primary' | 'secondary'>(() => {
  switch (plan.value) {
    case 'PREMIUM':
      return 'primary'
    case 'MAX':
      return 'secondary'
    case 'FREE':
    default:
      return 'neutral'
  }
})

const planBadgeLabel = computed<string>(() => {
  switch (plan.value) {
    case 'PREMIUM':
      return t('billing.planBadge.premium')
    case 'MAX':
      return t('billing.planBadge.max')
    case 'FREE':
    default:
      return t('billing.planBadge.free')
  }
})
</script>

<template>
  <div class="h-full min-h-0 overflow-hidden flex flex-col">
    <!-- Header -->
    <div class="flex items-center gap-2 p-2">
      <UButton
          variant="ghost"
          icon="i-heroicons-bars-3"
          :aria-label="collapsed ? t('sidebar.openSidebar') : t('sidebar.closeSidebar')"
          class="btn-interactive"
          @click="emit('toggle')"
      />
      <div v-if="!collapsed" class="flex-1 font-semibold truncate tracking-tight">
        {{ t('sidebar.chatsTitle') }}
      </div>
      <UButton
          v-if="!collapsed"
          color="primary"
          variant="soft"
          icon="i-heroicons-plus"
          :label="t('sidebar.newChat')"
          class="btn-interactive"
          @click="handleNewChat"
      />
    </div>

    <!-- List -->
    <nav class="flex-1 min-h-0 overflow-y-auto px-2 pb-2 scrollbar-thin">
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
                  :label="t('common.save')"
                  class="btn-interactive"
                  :loading="processingChatId === conversation.session.chatId"
                  @click="saveTitle(conversation.session.chatId)"
                />
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  :label="t('common.cancel')"
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
                  {{ conversation.session.title || t('sidebar.newChatPlaceholder') }}
                </span>
              </button>

              <div v-if="!collapsed" class="flex items-center gap-1 pr-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  icon="i-heroicons-pencil-square"
                  :aria-label="t('sidebar.renameChat')"
                  class="btn-interactive"
                  :disabled="processingChatId === conversation.session.chatId"
                  @click.stop="startEditing(conversation.session.chatId, conversation.session.title)"
                />
                <UButton
                  size="xs"
                  variant="ghost"
                  color="error"
                  icon="i-heroicons-trash"
                  :aria-label="t('sidebar.deleteChat')"
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
      <template v-if="isAuthenticated">
        <div
          v-if="!collapsed"
          class="px-2 pt-1 pb-2 flex items-center justify-between"
        >
          <span class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('billing.planLabel') }}
          </span>
          <NuxtLink to="/pricing" class="no-underline">
            <UBadge
              :color="planBadgeColor"
              variant="subtle"
              :label="planBadgeLabel"
              :aria-label="t('billing.planBadge.ariaLabel')"
            />
          </NuxtLink>
        </div>
        <UButton
          variant="ghost"
          icon="i-heroicons-cpu-chip"
          :label="collapsed ? '' : t('sidebar.models')"
          :aria-label="t('sidebar.models')"
          to="/models"
          class="w-full justify-start btn-interactive"
        />
        <UButton
          variant="ghost"
          icon="i-heroicons-arrows-right-left"
          :label="collapsed ? '' : t('sidebar.compare')"
          :aria-label="t('sidebar.compare')"
          to="/compare"
          class="w-full justify-start btn-interactive"
        />
        <UButton
          variant="ghost"
          icon="i-heroicons-chart-bar"
          :label="collapsed ? '' : t('sidebar.usage')"
          :aria-label="t('sidebar.usage')"
          to="/usage"
          class="w-full justify-start btn-interactive"
        />
        <UButton
          variant="ghost"
          icon="i-heroicons-cog-6-tooth"
          :label="collapsed ? '' : t('sidebar.settings')"
          :aria-label="t('sidebar.settings')"
          to="/settings"
          class="w-full justify-start btn-interactive"
        />
        <UButton
          variant="ghost"
          icon="i-heroicons-user-plus"
          :label="collapsed ? '' : t('sidebar.switchAccount')"
          :aria-label="t('sidebar.switchGoogleAccount')"
          :disabled="loggingOut"
          class="w-full justify-start btn-interactive"
          @click="handleSwitchAccount"
        />
        <UButton
          variant="ghost"
          color="error"
          icon="i-heroicons-arrow-right-on-rectangle"
          :label="collapsed ? '' : t('sidebar.logout')"
          :aria-label="t('sidebar.logout')"
          :loading="loggingOut"
          class="w-full justify-start btn-interactive"
          @click="handleLogout"
        />
      </template>
      <template v-else>
        <UButton
          variant="ghost"
          color="neutral"
          :icon="themeToggleIcon"
          :label="collapsed ? '' : themeToggleLabel"
          :aria-label="themeToggleLabel"
          class="w-full justify-start btn-interactive"
          @click="toggleColorMode"
        />
        <UButton
          color="primary"
          variant="soft"
          icon="i-heroicons-arrow-right-on-rectangle"
          :label="collapsed ? '' : t('auth.loginButton')"
          :aria-label="t('auth.loginButton')"
          class="w-full justify-start btn-interactive"
          @click="openLogin"
        />
      </template>
    </div>

    <!-- Guest import prompt -->
    <UModal v-model:open="guestImportOpen" :ui="{ width: 'max-w-md' }">
      <template #content>
        <div class="p-5 space-y-4">
          <div class="flex items-start gap-3">
            <div class="p-2 rounded-lg bg-primary-100 text-primary-600 dark:bg-primary-500/15 dark:text-primary-300">
              <UIcon name="i-heroicons-inbox-arrow-down" class="size-5" />
            </div>
            <div class="flex-1 min-w-0">
              <h3 class="text-base font-semibold tracking-tight">{{ t('sidebar.guestImportTitle') }}</h3>
              <p class="text-sm text-gray-600 dark:text-gray-400 mt-1">
                {{ t('sidebar.guestImportDescription', { count: guestChatCount() }) }}
              </p>
            </div>
          </div>
          <div class="flex justify-end gap-2 pt-2 flex-wrap">
            <UButton
              variant="ghost"
              color="neutral"
              :label="t('sidebar.guestImportDismiss')"
              :disabled="importingGuestChats"
              @click="dismissGuestImport"
            />
            <UButton
              variant="outline"
              color="neutral"
              :label="t('sidebar.guestImportKeepLocal')"
              :disabled="importingGuestChats"
              @click="keepGuestChatsLocal"
            />
            <UButton
              color="primary"
              icon="i-heroicons-inbox-arrow-down"
              :label="t('sidebar.guestImportAction')"
              :loading="importingGuestChats"
              @click="handleGuestImport"
            />
          </div>
        </div>
      </template>
    </UModal>

    <!-- Delete confirmation -->
    <UModal v-model:open="deleteModalOpen" :ui="{ width: 'max-w-md' }">
      <template #content>
        <div class="p-5 space-y-4">
          <div class="flex items-start gap-3">
            <div class="p-2 rounded-lg bg-red-100 text-red-600 dark:bg-red-500/15 dark:text-red-400">
              <UIcon name="i-heroicons-trash" class="size-5" />
            </div>
            <div class="flex-1 min-w-0">
              <h3 class="text-base font-semibold tracking-tight">{{ t('sidebar.deleteConfirmTitle') }}</h3>
              <i18n-t
                keypath="sidebar.deleteConfirmDescription"
                tag="p"
                class="text-sm text-gray-600 dark:text-gray-400 mt-1"
              >
                <template #title>
                  <span class="font-medium">{{ deleteTarget?.title }}</span>
                </template>
              </i18n-t>
            </div>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <UButton
              variant="ghost"
              color="neutral"
              :label="t('common.cancel')"
              :disabled="processingChatId === deleteTarget?.chatId"
              @click="deleteTarget = null"
            />
            <UButton
              color="error"
              icon="i-heroicons-trash"
              :label="t('sidebar.deleteConfirmAction')"
              :loading="processingChatId === deleteTarget?.chatId"
              @click="confirmDelete"
            />
          </div>
        </div>
      </template>
    </UModal>
  </div>
</template>
