// Vue I18n options that can't be expressed via @nuxtjs/i18n module options alone.
// See AGENTS.md (project root) for the i18n conventions.
export default defineI18nConfig(() => ({
  legacy: false,
  fallbackLocale: 'de',
  // Every string in i18n/locales/{de,en}.json is treated as plain text – no
  // HTML escaping side-effects beyond what Vue does for {{ }} interpolation.
  warnHtmlMessage: false,
  missingWarn: false,
  fallbackWarn: false
}))
