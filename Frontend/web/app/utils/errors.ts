/**
 * Maps raw errors (from `fetch`, API clients, etc.) to a translation key from
 * the `errors.*` namespace. Use {@link useTranslatedError} (or pass the
 * resulting key to your translator) to obtain a localized string.
 *
 * Returns either:
 *   - an `errors.*` translation key (string), or
 *   - the raw error message itself when it's short and otherwise meaningful
 *     (e.g. validation messages from the backend).
 */
export type FriendlyErrorResult = {
  /** A translation key like `errors.backendUnreachable`, or `null` when the
   * raw message should be displayed verbatim. */
  key: string | null
  /** Raw fallback text – the original error message if usable, otherwise an
   * `errors.fallback` translation key. */
  raw: string
}

export function friendlyErrorKey(err: unknown): FriendlyErrorResult {
  if (!err) return { key: 'errors.fallback', raw: '' }

  const raw = err instanceof Error ? err.message : String(err)
  const lower = raw.toLowerCase()
  const apiKind = typeof err === 'object' && err !== null && 'kind' in err
    ? String((err as { kind?: unknown }).kind)
    : null
  const apiCode = typeof err === 'object' && err !== null && 'code' in err
    ? String((err as { code?: unknown }).code || '').toUpperCase()
    : ''

  if (apiCode === 'DOCUMENT_PROCESSING_TIMEOUT') {
    return { key: 'errors.documentProcessingTimeout', raw }
  }
  if (apiCode === 'DOCUMENT_EMPTY') {
    return { key: 'errors.documentNoText', raw }
  }
  if (apiCode === 'DOCUMENT_TYPE_UNSUPPORTED') {
    return { key: 'errors.unsupportedDocumentType', raw }
  }
  if (
    apiCode === 'DOCUMENT_PROCESSING_FAILED'
    || apiCode === 'DOCUMENT_UPLOAD_EMPTY_RESPONSE'
    || apiCode === 'DOCUMENT_PAYLOAD_MISSING'
    || apiCode === 'DOCUMENT_EXTRACTION_FAILED'
  ) {
    return { key: 'errors.documentProcessingFailed', raw }
  }

  if (apiKind === 'timeout' || lower.includes('timed out') || lower.includes('idle for too long')) {
    return { key: 'errors.timeout', raw }
  }

  if (
    lower.includes('failed to fetch') ||
    lower.includes('networkerror') ||
    lower.includes('load failed') ||
    lower.includes('err_connection')
  ) {
    return { key: 'errors.backendUnreachable', raw }
  }
  if (lower.includes('abort')) {
    return { key: 'errors.aborted', raw }
  }

  if (/\b401\b/.test(raw) || lower.includes('unauthorized')) {
    return { key: 'errors.unauthorized', raw }
  }
  if (/\b403\b/.test(raw) || lower.includes('forbidden') || lower.includes('csrf')) {
    return { key: 'errors.forbidden', raw }
  }
  if (/\b404\b/.test(raw)) {
    return { key: 'errors.notFound', raw }
  }
  if (/\b413\b/.test(raw) || lower.includes('payload too large')) {
    return { key: 'errors.payloadTooLarge', raw }
  }
  if (/\b429\b/.test(raw) || lower.includes('rate limit')) {
    return { key: 'errors.rateLimit', raw }
  }
  if (lower.includes('daily_cap_exceeded')) {
    return { key: 'errors.dailyCapExceeded', raw }
  }
  if (lower.includes('parallel_streams_exceeded')) {
    return { key: 'errors.parallelStreamsExceeded', raw }
  }
  if (lower.includes('model_not_allowed')) {
    return { key: 'errors.modelNotAllowed', raw }
  }
  if (lower.includes('tool_not_allowed')) {
    return { key: 'errors.toolNotAllowed', raw }
  }
  if (lower.includes('api_key_required')) {
    return { key: 'errors.apiKeyRequired', raw }
  }
  if (lower.includes('managed_provider_unconfigured')) {
    return { key: 'errors.managedProviderUnconfigured', raw }
  }
  if (lower.includes('cancelled') || lower.includes('canceled') || lower.includes('abort')) {
    return { key: 'errors.cancelled', raw }
  }
  if (lower.includes('unsupported document type')) {
    return { key: 'errors.unsupportedDocumentType', raw }
  }
  if (lower.includes('document contains no extractable text')) {
    return { key: 'errors.documentNoText', raw }
  }
  if (lower.includes('encrypted pdf')) {
    return { key: 'errors.encryptedPdfUnsupported', raw }
  }
  if (/\b5\d{2}\b/.test(raw)) {
    return { key: 'errors.serverError', raw }
  }

  if (lower.includes('api key') || lower.includes('api-key')) {
    return { key: 'errors.apiKey', raw }
  }

  if (raw.length > 0 && raw.length < 200) {
    // Pass through raw (likely backend-provided) message, no translation key.
    return { key: null, raw }
  }
  return { key: 'errors.fallback', raw }
}
