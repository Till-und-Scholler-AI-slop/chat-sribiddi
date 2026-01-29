<script setup lang="ts">
import { Chat } from '@ai-sdk/vue'

const input = ref('')

const models = [
  { label: 'GPT-4o', value: 'gpt-4o' },
  { label: 'GPT-4o mini', value: 'gpt-4o-mini' },
  { label: 'GPT-5 Nano', value: 'gpt-5-nano' }
] as const

const model = ref<(typeof models)[number]>(models[2]) // default: GPT-5 Nano

const chat = new Chat({
  onError(err) {
    console.error(err)
  }
})

async function onSubmit() {
  const text = input.value.trim()
  if (!text) return

  // Wichtig: model dynamisch mitschicken (nicht als "body" beim init, sonst kann es stale werden)
  // Je nach AI-SDK-Version klappt das als 2. Parameter:
  await chat.sendMessage(
      { text },
      { body: { model: model.value.value } }
  )

  input.value = ''
}
</script>

<template>
  <UDashboardPanel>
    <template #body>
      <UContainer class="py-4">
        <UChatMessages :messages="chat.messages" :status="chat.status" />
      </UContainer>
    </template>

    <template #footer>
      <UContainer class="pb-4 sm:pb-6">
        <UChatPrompt v-model="input" :error="chat.error" @submit="onSubmit">
          <!-- Dropdown links im Prompt -->
          <template #header>
            <USelectMenu
                v-model="model"
                :items="models"
                class="w-44"
                variant="subtle"
                color="neutral"
                :ui="{ base: 'text-sm' }"
            />
          </template>

          <UChatPromptSubmit
              :status="chat.status"
              @stop="chat.stop()"
              @reload="chat.regenerate()"
          />
        </UChatPrompt>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
