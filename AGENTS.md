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

# Agent guidelines: manual owner-setup steps

Some features require the repository owner to perform manual, out-of-band steps
(creating accounts, registering secrets, configuring external services) that a
coding agent cannot automate. **Every AI/agent working on this codebase must
append such steps here** whenever they introduce a feature that requires
owner action — this file is the single source of truth so the owner can find
everything in one place.

Format per feature: a short heading, the ticket id, and a bullet list of the
exact manual steps plus any environment variables / secrets involved.

## BILL-7 — OpenRouter managed-mode for Free tier

To enable the Free-tier managed (platform-paid) chat path through OpenRouter,
the owner must:

1. **Create an OpenRouter account** at <https://openrouter.ai> and generate a
   server-side API key (Dashboard → Keys → *Create Key*). Top up credits as
   needed; free-tier traffic uses `:free`-suffixed models, which are zero-cost
   but still require a valid key for routing.
2. **Store the key as a deployment secret** named `OPENROUTER_API_KEY` (e.g.
   GitHub Actions secret, Caddy/Docker env, `.env` file for local dev). The
   backend reads it via `app.billing.managed-keys.openrouter` — a blank value
   disables managed mode for OpenRouter entirely.
3. **Configure OpenRouter attribution** (optional but recommended; OpenRouter
   uses these to group requests on their dashboard):
   - `OPENROUTER_APP_NAME` — sent as the `X-Title` header
     (default: `chat-sribiddi`).
   - `OPENROUTER_REFERRER` — sent as the `HTTP-Referer` header; should point to
     the public URL of the deployment (default: value of `APP_FRONTEND_URL`,
     falling back to `https://chat-sribiddi.local`).
4. **Verify Free-tier behaviour after deployment**: sign in as a Free user
   without any BYOK key stored, open the model picker, and confirm only
   OpenRouter `:free` models are listed. Selecting a paid model such as
   `gpt-5.2` must be rejected with a `PlanRestrictionException`
   (HTTP 402, `MODEL_NOT_ALLOWED`) and an upgrade hint.

No DB migrations or additional OAuth scopes are required for BILL-7.

## BILL-11 — Stripe Checkout & Customer Portal

To enable the paid-tier billing flow (`POST /billing/checkout-session`,
`POST /billing/portal-session`, `GET /billing/me`, `GET /billing/usage`) the
owner must:

1. **Create a Stripe account** at <https://stripe.com> and register the
   required products in the Stripe dashboard:
   - *Premium* plan with a **monthly** recurring Price and a **yearly**
     recurring Price.
   - *Max* plan with a **monthly** recurring Price and a **yearly** recurring
     Price.
2. **Grab the Price IDs** (`price_…`) from each Price's detail page and set
   them as deployment secrets:
   - `STRIPE_PRICE_ID_PREMIUM_MONTH`
   - `STRIPE_PRICE_ID_PREMIUM_YEAR`
   - `STRIPE_PRICE_ID_MAX_MONTH`
   - `STRIPE_PRICE_ID_MAX_YEAR`
3. **Store the Stripe secret key** (Developers → API keys) as
   `STRIPE_SECRET_KEY`. Use the *test* key for staging and the *live* key for
   production. A blank key causes the billing endpoints to return HTTP 503 so
   the rest of the app keeps working.
4. **Store the webhook signing secret** as `STRIPE_WEBHOOK_SECRET`. This is
   consumed by the BILL-10 webhook handler; it is declared under the same
   `app.billing.stripe.*` namespace here so all Stripe configuration lives in
   one place.
5. **Enable the Stripe Customer Portal** (Settings → Billing → Customer Portal)
   and configure the allowed actions (cancel, switch plan, update payment
   method).
6. **Configure the redirect URLs** (optional — defaults are derived from
   `APP_FRONTEND_URL`):
   - `STRIPE_SUCCESS_URL` — where Stripe Checkout sends users after a
     successful subscription. May contain the
     `{CHECKOUT_SESSION_ID}` placeholder which Stripe substitutes server-side.
   - `STRIPE_CANCEL_URL` — where Stripe sends users who abandon Checkout.
   - `STRIPE_PORTAL_RETURN_URL` — where the Customer Portal returns users.
7. **Verify end-to-end** after deployment: sign in, call
   `POST /billing/checkout-session` with `{"plan":"PREMIUM","interval":"MONTH"}`,
   complete checkout with a test card (e.g. `4242 4242 4242 4242`), wait for the
   webhook to flip the user's plan, and confirm `GET /billing/me` returns
   `{"plan":"PREMIUM", …}`.

