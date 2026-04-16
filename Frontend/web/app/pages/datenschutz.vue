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
          Diese Datenschutzerkl&auml;rung informiert dar&uuml;ber, welche personenbezogenen Daten bei der
          Nutzung von Chat Sribiddi verarbeitet werden.
        </p>

        <p>
          <strong>Verantwortlicher</strong><br>
          Rafael Till<br>
          Scheffelstra&szlig;e 55<br>
          76135 Karlsruhe<br>
          Deutschland<br>
          E-Mail: tillrafael@icloud.com
        </p>

        <p>
          <strong>Welche Daten werden verarbeitet</strong><br>
          Beim Aufruf der Website werden technisch erforderliche Verbindungsdaten verarbeitet,
          insbesondere IP-Adresse, Zeitstempel, Browser-Informationen sowie sicherheitsrelevante
          Protokolldaten. Beim Login &uuml;ber Google werden die von Google bereitgestellten
          Kontoinformationen verarbeitet, soweit sie f&uuml;r Anmeldung und Nutzerkonto erforderlich sind.
        </p>

        <p>
          <strong>Nutzung der Chat-Funktion</strong><br>
          Bei der Nutzung des Dienstes werden Ihre Eingaben, hochgeladene Inhalte, Modell-Auswahl,
          erzeugte Antworten sowie von Ihnen hinterlegte API-Schl&uuml;ssel verarbeitet. API-Schl&uuml;ssel
          werden verschl&uuml;sselt gespeichert. Inhalte der Chat-Funktion werden an den jeweils von Ihnen
          ausgew&auml;hlten KI-Anbieter &uuml;bermittelt, soweit dies zur Bearbeitung Ihrer Anfrage erforderlich
          ist.
        </p>

        <p>
          <strong>Zwecke und Rechtsgrundlagen</strong><br>
          Die Verarbeitung erfolgt zur Bereitstellung des Dienstes und der Anmeldung
          (Art. 6 Abs. 1 lit. b DSGVO) sowie zur Gew&auml;hrleistung eines sicheren und stabilen Betriebs
          (Art. 6 Abs. 1 lit. f DSGVO).
        </p>

        <p>
          <strong>Empf&auml;nger der Daten</strong><br>
          Empf&auml;nger der Daten sind technische Dienstleister f&uuml;r Hosting und Authentifizierung sowie
          die von Ihnen ausgew&auml;hlten KI-Anbieter. Eine Weitergabe erfolgt nur, soweit sie f&uuml;r den
          Betrieb von Chat Sribiddi erforderlich ist.
        </p>

        <p>
          <strong>Speicherdauer</strong><br>
          Personenbezogene Daten werden nur so lange gespeichert, wie dies f&uuml;r die genannten Zwecke
          erforderlich ist oder gesetzliche Aufbewahrungspflichten bestehen. Chat-Inhalte und
          hinterlegte API-Schl&uuml;ssel bleiben gespeichert, bis sie gel&ouml;scht werden oder der Zweck der
          Speicherung entf&auml;llt. Hinterlegte API-Schl&uuml;ssel k&ouml;nnen Sie jederzeit auf der
          Einstellungsseite entfernen; bei L&ouml;schung Ihres Kontos werden sie zusammen mit den
          zugeh&ouml;rigen Chat-Daten entfernt.
        </p>

        <p>
          <strong>Ihre Rechte</strong><br>
          Sie haben das Recht auf Auskunft, Berichtigung, L&ouml;schung, Einschr&auml;nkung der Verarbeitung,
          Daten&uuml;bertragbarkeit sowie Beschwerde bei einer Datenschutz-Aufsichtsbeh&ouml;rde.
        </p>
      </div>

      <div class="flex flex-wrap gap-3">
        <UButton to="/login" variant="outline" color="neutral" label="Zurueck zum Login" />
        <UButton to="/impressum" variant="ghost" color="neutral" label="Impressum" />
      </div>
    </div>
  </UCard>
</template>
