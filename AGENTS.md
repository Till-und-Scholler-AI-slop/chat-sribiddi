# Agent guidelines: internationalization (i18n)

This file is intentionally short and is the **single source of truth** for the
translation conventions of this repository. Every coding agent (and human
contributor) must follow it whenever they add or touch user-facing text.

## TL;DR

- The frontend ships in **two locales: `de` (default) and `en`**.
- Locale messages live in [`Frontend/web/i18n/locales/de.json`](Frontend/web/i18n/locales/de.json) and [`Frontend/web/i18n/locales/en.json`](Frontend/web/i18n/locales/en.json).
- The i18n setup is configured via [`@nuxtjs/i18n`](https://i18n.nuxtjs.org/) in [`Frontend/web/nuxt.config.ts`](Frontend/web/nuxt.config.ts) and [`Frontend/web/i18n/i18n.config.ts`](Frontend/web/i18n/i18n.config.ts).
- **Never hard-code user-facing strings** in `.vue` files, composables or utilities.

## How to add a new string

1. Pick a stable, namespaced key, e.g. `chat.uploadFailedTitle` or
   `settings.deleteAccount`. Reuse existing namespaces (`common`, `auth`,
   `sidebar`, `chat`, `tools`, `models`, `settings`, `errors`, `legal`,
   `language`).
2. Add the key with the **German** translation to `i18n/locales/de.json` and the
   **English** translation to `i18n/locales/en.json`. Both files must always
   contain the same keys – do not commit a key in only one locale.
3. Reference it via the i18n composable / template helper:
   - In `<script setup>`:
     ```ts
     const { t } = useI18n()
     toast.add({ title: t('chat.uploadFailedTitle') })
     ```
   - In templates:
     ```html
     <UButton :label="t('common.save')" />
     <p>{{ t('chat.welcomeIntro') }}</p>
     ```
   - For interpolation use named placeholders, e.g.
     `t('models.useModelToastTitle', { label: model.label })` mapped to
     `"{label} selected"` in the JSON.
4. **Special characters in messages**: vue-i18n treats `@` and `:` as control
   characters for linked messages. Wrap a literal `@` (e.g. inside e-mail
   addresses) in single quotes inside the message: `tillrafael{'@'}icloud.com`.

## Errors & toasts

- For thrown errors, use the [`useTranslatedError`](Frontend/web/app/composables/useTranslatedError.ts)
  composable. It maps a raw error to a friendly translated string via the
  `errors.*` namespace, falling back to the raw backend message when it's
  short and meaningful.
  ```ts
  const tt = useTranslatedError()
  toast.add({ description: tt(err) })
  ```
- The mapping logic lives in
  [`Frontend/web/app/utils/errors.ts`](Frontend/web/app/utils/errors.ts) and
  is covered by `app/utils/__tests__/errors.test.ts` – update both when adding
  new error categories.

## Language switching

- Users can switch the language from the **sidebar dropdown** at the bottom
  (next to the dark-mode toggle) or in **Settings → Language**.
- The active locale is automatically persisted in the
  `i18n_redirected` cookie by `@nuxtjs/i18n` and detected from the browser on
  first visit (with German as the configured fallback).
- The `<html lang>` attribute is updated automatically by the i18n module
  based on each locale's `language` field declared in `nuxt.config.ts`
  (`de-DE` / `en-US`).

## Backend strings

The Spring Boot backend currently does not return localized strings. If you
introduce a backend response that the user must read directly (validation
messages, error descriptions), prefer returning a stable error code that the
frontend can map to an `errors.*` translation, instead of returning a hard-coded
German or English sentence.

## Validation checklist for any PR that touches UI text

- [ ] No new hard-coded German/English strings outside `i18n/locales/*.json`.
- [ ] Every new key exists in **both** `de.json` and `en.json` with the same
      structure.
- [ ] `cd Frontend/web && npm test` still passes.
- [ ] `cd Frontend/web && npm run build` still passes (vue-i18n compiles
      messages at build time and will fail if `@`/`:` is misused).
