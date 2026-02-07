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
        <p class="text-sm text-gray-600 dark:text-gray-400">Angaben gemaess Paragraph 5 TMG</p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>
          <strong>Chat Sribiddi</strong><br>
          Musterstrasse 1<br>
          12345 Musterstadt<br>
          Deutschland
        </p>

        <p>
          Vertreten durch:<br>
          Max Mustermann
        </p>

        <p>
          Kontakt:<br>
          Telefon: +49 0000 000000<br>
          E-Mail: kontakt@example.com
        </p>

        <p>
          Verantwortlich fuer den Inhalt nach Paragraph 55 Abs. 2 RStV:<br>
          Max Mustermann, Anschrift wie oben
        </p>
      </div>

      <UAlert
        color="warning"
        variant="soft"
        icon="i-heroicons-exclamation-triangle"
        title="Vorlage anpassen"
        description="Bitte die Platzhalter im Impressum durch die korrekten Unternehmensdaten ersetzen."
      />

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" label="Zurueck zum Login" />
        <UButton to="/datenschutz" variant="ghost" color="neutral" label="Datenschutz" />
      </div>
    </div>
  </UCard>
</template>
