import { describe, expect, it } from 'vitest'
import { friendlyErrorMessage } from '../errors'

describe('friendlyErrorMessage', () => {
  it('maps common network failures', () => {
    expect(friendlyErrorMessage('Failed to fetch')).toBe('Backend nicht erreichbar. Läuft der Server?')
    expect(friendlyErrorMessage('ERR_CONNECTION_REFUSED')).toBe('Backend nicht erreichbar. Läuft der Server?')
  })

  it('maps abort errors', () => {
    expect(friendlyErrorMessage(new Error('Request abort'))).toBe('Anfrage wurde abgebrochen.')
  })

  it('maps HTTP auth and permission errors', () => {
    expect(friendlyErrorMessage('401 Unauthorized')).toBe('Sitzung abgelaufen. Bitte melde dich erneut an.')
    expect(friendlyErrorMessage('Forbidden CSRF token mismatch')).toBe('Aktion nicht erlaubt. Bitte lade die Seite neu.')
  })

  it('maps common backend status and api key errors', () => {
    expect(friendlyErrorMessage('404 Not Found')).toBe('Ressource nicht gefunden.')
    expect(friendlyErrorMessage('413 Payload Too Large')).toBe('Anhang ist zu groß.')
    expect(friendlyErrorMessage('rate limit exceeded')).toBe('Zu viele Anfragen. Bitte kurz warten.')
    expect(friendlyErrorMessage('500 Internal Server Error')).toBe('Server-Fehler. Bitte später erneut versuchen.')
    expect(friendlyErrorMessage('API key invalid')).toBe(
      'API-Schlüssel ungültig oder fehlt. Bitte in den Einstellungen prüfen.'
    )
  })

  it('returns a short unknown message unchanged', () => {
    expect(friendlyErrorMessage('Etwas passt nicht')).toBe('Etwas passt nicht')
  })

  it('falls back for empty or very long messages', () => {
    const fallback = 'Eigener Fallback'
    expect(friendlyErrorMessage('', fallback)).toBe(fallback)
    expect(friendlyErrorMessage('x'.repeat(200), fallback)).toBe(fallback)
  })
})
