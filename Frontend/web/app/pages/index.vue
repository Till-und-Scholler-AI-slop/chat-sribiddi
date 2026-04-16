<script setup lang="ts">
definePageMeta({
  layout: false
})

if (import.meta.server) {
  // The global auth middleware runs only on the client, so on SSR we just
  // render nothing and let the client-side redirect take over.
} else {
  // Authenticated users land here after Google OAuth (Spring redirects to "/").
  // The global auth middleware already ensures we are signed in, so simply
  // forward to the chat workspace which is the actual app entry point.
  await navigateTo('/chat', { replace: true })
}
</script>

<template>
  <div />
</template>
