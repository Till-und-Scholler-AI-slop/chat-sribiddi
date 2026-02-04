<script setup lang="ts">
definePageMeta({
  layout: 'default',
  middleware: ['auth']
})

const { settings, hydrated, reset } = useUserSettings()
const toast = useToast()
const saving = ref(false)

async function handleSubmit() {
  saving.value = true
  await nextTick()
  toast.add({
    title: 'Settings saved',
    description: 'Backend URL updated.',
    color: 'primary'
  })
  saving.value = false
}
</script>

<template>
  <UDashboardPanel>
    <template #header>
      <UDashboardNavbar title="Settings" />
    </template>

    <template #body>
      <UContainer class="py-6 space-y-6">
        <UAlert
          title="Backend-managed API keys"
          color="neutral"
          description="Model API keys are stored server-side after login. Frontend does not keep them."
          icon="i-heroicons-key"
        />

        <div v-if="!hydrated" class="space-y-3">
          <USkeleton class="h-12" />
          <USkeleton class="h-12" />
          <USkeleton class="h-12" />
        </div>

        <UForm v-else @submit="handleSubmit">
          <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <UFormGroup
              label="Backend base URL"
              description="The REST API from the Spring Boot backend."
              name="backendBaseUrl"
            >
              <UInput
                v-model="settings.backendBaseUrl"
                placeholder="http://localhost:8080"
                icon="i-heroicons-globe-alt"
              />
            </UFormGroup>
          </div>

          <div class="mt-6 flex flex-wrap gap-3">
            <UButton
              type="submit"
              color="primary"
              :loading="saving"
              icon="i-heroicons-check-circle"
              label="Save"
            />
            <UButton
              type="button"
              variant="outline"
              icon="i-heroicons-rectangle-stack"
              label="Reset"
              @click="reset()"
            />
          </div>
        </UForm>
      </UContainer>
    </template>
  </UDashboardPanel>
</template>
