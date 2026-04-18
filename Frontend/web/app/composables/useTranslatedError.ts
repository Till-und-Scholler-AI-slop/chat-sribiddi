import { friendlyErrorKey } from '~/utils/errors'

/**
 * Returns a function that converts a thrown error into a localized,
 * user-friendly string using the active i18n locale.
 *
 * Usage in a component / composable:
 *   const tt = useTranslatedError()
 *   toast.add({ title: '…', description: tt(err) })
 *
 * The optional fallback key is used when the error didn't match any known
 * pattern *and* the raw message wasn't suitable for direct display – it can
 * be used to provide a more contextual "Backend not reachable?" message.
 */
export function useTranslatedError() {
  const { t, te } = useI18n()

  return function translateError(err: unknown, fallbackKey: string = 'errors.fallback'): string {
    const result = friendlyErrorKey(err)
    if (result.key) {
      return t(result.key)
    }
    if (result.raw) {
      return result.raw
    }
    if (te(fallbackKey)) {
      return t(fallbackKey)
    }
    return fallbackKey
  }
}
