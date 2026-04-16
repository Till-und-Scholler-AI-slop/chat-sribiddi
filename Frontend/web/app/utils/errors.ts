/**
 * Maps raw errors (from `fetch`, API clients, etc.) to a user-friendly
 * German message. The mapping only covers the most common cases; unknown
 * errors fall back to a generic message (so we never expose raw stack
 * traces or provider internals in toasts).
 */
export function friendlyErrorMessage(
  err: unknown,
  fallback = 'Unbekannter Fehler. Bitte versuche es erneut.'
): string {
  if (!err) return fallback

  const raw = err instanceof Error ? err.message : String(err)
  const lower = raw.toLowerCase()

  if (
    lower.includes('failed to fetch') ||
    lower.includes('networkerror') ||
    lower.includes('load failed') ||
    lower.includes('err_connection')
  ) {
    return 'Backend nicht erreichbar. Läuft der Server?'
  }
  if (lower.includes('abort')) {
    return 'Anfrage wurde abgebrochen.'
  }

  if (/\b401\b/.test(raw) || lower.includes('unauthorized')) {
    return 'Sitzung abgelaufen. Bitte melde dich erneut an.'
  }
  if (/\b403\b/.test(raw) || lower.includes('forbidden') || lower.includes('csrf')) {
    return 'Aktion nicht erlaubt. Bitte lade die Seite neu.'
  }
  if (/\b404\b/.test(raw)) {
    return 'Ressource nicht gefunden.'
  }
  if (/\b413\b/.test(raw) || lower.includes('payload too large')) {
    return 'Anhang ist zu groß.'
  }
  if (/\b429\b/.test(raw) || lower.includes('rate limit')) {
    return 'Zu viele Anfragen. Bitte kurz warten.'
  }
  if (/\b5\d{2}\b/.test(raw)) {
    return 'Server-Fehler. Bitte später erneut versuchen.'
  }

  if (lower.includes('api key') || lower.includes('api-key')) {
    return 'API-Schlüssel ungültig oder fehlt. Bitte in den Einstellungen prüfen.'
  }

  if (raw.length > 0 && raw.length < 200) {
    return raw
  }
  return fallback
}
