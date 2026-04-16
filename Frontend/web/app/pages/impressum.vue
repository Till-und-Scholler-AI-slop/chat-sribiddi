<script setup lang="ts">
definePageMeta({
  layout: 'auth'
})
</script>

<template>
  <UCard class="mx-auto max-w-2xl">
    <div class="space-y-6">
      <div class="space-y-1">
        <h1 class="text-2xl font-semibold">Impressum</h1>
        <p class="text-sm text-gray-600 dark:text-gray-400">Angaben gem&auml;&szlig; &sect; 5 DDG</p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>
          <strong>Chat Sribiddi</strong><br>
          Rafael Till<br>
          Scheffelstra&szlig;e 55<br>
          76135 Karlsruhe<br>
          Deutschland
        </p>

        <p>
          Kontakt:<br>
          E-Mail: tillrafael@icloud.com
        </p>

        <p>
          Verantwortlich f&uuml;r die Inhalte dieser Website:<br>
          Rafael Till, Anschrift wie oben
        </p>
      </div>

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" label="Zurueck zum Login" />
        <UButton to="/datenschutz" variant="ghost" color="neutral" label="Datenschutz" />
      </div>
    </div>
  </UCard>
</template>
