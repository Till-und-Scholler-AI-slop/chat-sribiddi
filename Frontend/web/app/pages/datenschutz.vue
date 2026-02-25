<script setup lang="ts">
definePageMeta({
  layout: 'auth'
})
</script>

<template>
  <UCard class="mx-auto max-w-2xl">
    <div class="space-y-6">
      <div class="space-y-1">
        <h1 class="text-2xl font-semibold">Datenschutz</h1>
        <p class="text-sm text-gray-600 dark:text-gray-400">
          Informationen zur Verarbeitung personenbezogener Daten
        </p>
      </div>

      <div class="space-y-4 text-sm text-gray-700 dark:text-gray-300">
        <p>
          Wir verarbeiten personenbezogene Daten nur, soweit dies fuer den Betrieb dieser Anwendung
          erforderlich ist.
        </p>

        <p>
          <strong>Verantwortlicher</strong><br>
          Chat Sribiddi<br>
          Musterstrasse 1, 12345 Musterstadt, Deutschland<br>
          E-Mail: datenschutz@example.com
        </p>

        <p>
          <strong>Welche Daten werden verarbeitet</strong><br>
          Beim Login koennen technische Verbindungsdaten und von Google bereitgestellte
          Profildaten verarbeitet werden. Bei der Nutzung des Chats werden Eingaben und Antworten
          zur Bereitstellung des Dienstes verarbeitet.
        </p>

        <p>
          <strong>Zweck und Rechtsgrundlage</strong><br>
          Die Verarbeitung erfolgt zur Bereitstellung der Anmeldung und der Chat-Funktion
          (Art. 6 Abs. 1 lit. b DSGVO) sowie zur Sicherstellung des sicheren Betriebs
          (Art. 6 Abs. 1 lit. f DSGVO).
        </p>

        <p>
          <strong>Speicherdauer</strong><br>
          Daten werden nur so lange gespeichert, wie es fuer den jeweiligen Zweck erforderlich ist
          oder gesetzliche Aufbewahrungspflichten bestehen.
        </p>

        <p>
          <strong>Ihre Rechte</strong><br>
          Sie haben das Recht auf Auskunft, Berichtigung, Loeschung, Einschraenkung der Verarbeitung,
          Datenuebertragbarkeit sowie Beschwerde bei einer Aufsichtsbehoerde.
        </p>
      </div>

      <UAlert
        color="warning"
        variant="soft"
        icon="i-heroicons-exclamation-triangle"
        title="Vorlage anpassen"
        description="Diese Datenschutzerklaerung ist eine Vorlage und ersetzt keine Rechtsberatung."
      />

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" label="Zurueck zum Login" />
        <UButton to="/impressum" variant="ghost" color="neutral" label="Impressum" />
      </div>
    </div>
  </UCard>
</template>
