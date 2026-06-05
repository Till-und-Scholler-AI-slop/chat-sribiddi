<script setup lang="ts">
definePageMeta({
  layout: 'default'
})

const { status, checkAuth } = useAuth()

onMounted(async () => {
  await checkAuth()
  await navigateTo(status.value === 'authenticated' ? '/settings' : '/chat', { replace: true })
})
</script>

<template>
  <div />
</template>
