# Monetization Plan – Plan A (verfeinert)

> Ziel: Drei klar abgegrenzte Tiers. Free finanziert sich durch Werbung + BYOK + kostenlose OpenRouter-Modelle. Premium und Max bieten kuratierten Zugriff auf bezahlte Modelle, abgerechnet über Stripe.

---

## 1. Tier-Übersicht

| Feature | **Free** | **Premium** (~9,99 €/Monat) | **Max** (~24,99 €/Monat) |
|---|---|---|---|
| BYOK (eigene API-Keys) | ✅ Alle Provider | ✅ Alle Provider | ✅ Alle Provider |
| Managed Modelle | OpenRouter "free" Modelle (`:free`-Suffix) | „Gute" Modelle: GPT-5.2-mini, Gemini 3 Flash, Claude Sonnet 4.6/Haiku 4.5, GPT-4.1 | Alle Premium-Modelle + Top-Tier: Claude Opus 4.7, GPT-5.2, Gemini 3.1 Pro, o3 |
| Werbung | ✅ Eingeblendet | ❌ Werbefrei | ❌ Werbefrei |
| Brave Web-Search (managed) | ❌ (nur via BYOK Brave-Key) | ✅ inkludiert (Fair-Use) | ✅ inkludiert (höheres Limit) |
| Attachments | 2 / Msg, 2 MB / 5 MB total | 5 / Msg, 5 MB / 20 MB total (heutige Defaults) | 10 / Msg, 20 MB / 100 MB total |
| Context-Length | 10 Messages | 20 Messages (heutiger Default) | 50 Messages |
| Parallele Streams | 1 | 3 | 10 |
| Daily Message Cap (Managed) | 50 Free-Tier-Calls / Tag | 500 Premium-Calls / Tag | 2000 Max-Calls / Tag |
| Custom-Instruction-Profile | 1 | 5 | unbegrenzt |
| Export (MD/JSON/PDF) | nur MD | MD + JSON + PDF | + Bulk-Export |
| Support | Community | E-Mail | Priority E-Mail |

> Hinweis Margen: Managed Calls werden intern über `usage_events` gegen ein Tageskontingent + (später) ein Soft-Credit-Budget pro Tier verrechnet. Bei Erreichen → Up-Sell-Hint, kein hartes Hard-Lock im Premium/Max wenn unter Fair-Use-Threshold.

---

## 2. Architektur-Eckpfeiler

* Neues Backend-Package `com.rft.chat_sribiddi.billing` (analog zu `chat`, `keyring`, `modelcatalog`).
* Neues Backend-Package `com.rft.chat_sribiddi.entitlement` (Plan-Gating, Feature-Flags, Quotas).
* Neues Backend-Package `com.rft.chat_sribiddi.ads` (Server-seitige Ad-Slot-Konfiguration, Tracking-Endpoints, Consent-Bridge).
* Erweiterung von `User`: `plan` (FREE/PREMIUM/MAX), `stripe_customer_id`, `plan_valid_until`, `plan_status`.
* Neue Migration `V7__billing_and_plans.sql` (siehe Ticket BILL-2).
* `DefaultProviderClientFactory` lernt einen weiteren Modus „MANAGED" mit Server-Env-Keys + OpenRouter als zusätzlicher Provider-Resolver.
* Frontend: neue Composables `useBillingApi`, `useEntitlements`, `useAds`, neue Pages `pricing.vue`, `billing.vue`, Erweiterung von `settings.vue`.

---

## 3. Epics

* **EPIC-1 – Plan & Entitlement Foundation** (Datenmodell, Plan-Resolver, Quotas, Gating).
* **EPIC-2 – Stripe Billing Integration** (Checkout, Webhooks, Customer Portal, Lifecycle).
* **EPIC-3 – Free-Tier OpenRouter (Free Models) Managed Mode** (Server-Key, Modell-Whitelist `:free`, Caps).
* **EPIC-4 – Premium/Max Managed Models** (Server-Keys, Modell-Whitelisting pro Tier, Quota-Enforcement).
* **EPIC-5 – Werbung (Free Tier)** (Ad-Slots, Consent, Provider-Integration, Frequency-Cap).
* **EPIC-6 – Frontend Plan-UX** (Pricing-Page, Billing-Page, Up-Sell-Banner, Plan-Badges, Modell-Auswahl-Filter).
* **EPIC-7 – Recht & Compliance** (AGB, Widerruf, Stripe Tax, Datenschutz Update für Ads + Stripe + OpenRouter, Cookie-Banner).
* **EPIC-8 – Observability & Anti-Abuse** (Usage-Metriken, Alarme, Rate-Limiting, Refund-Pfad).

---

## 4. Tickets (zum Kopieren)

> Format jeder Karte: **Titel**, **Type**, **Epic**, **Beschreibung**, **Akzeptanzkriterien**, **Technische Hinweise**, **Aufwand (S/M/L)**, **Abhängigkeiten**.

---

### BILL-1 — Tier-Modell & Feature-Matrix finalisieren
* **Type**: Spike / Doc
* **Epic**: EPIC-1
* **Beschreibung**: Tabelle aus Abschnitt 1 in der Produkt-Doku festschreiben, Preise validieren (USt., Stripe-Fees einrechnen), Modell-Whitelist pro Tier final entscheiden (insb. welche „guten" Modelle Premium und welche Top-Tier-Modelle Max).
* **Akzeptanz**:
  * `MONETIZATION_PLAN.md` enthält finalisierte Preise inkl. Brutto/Netto.
  * Whitelist liegt als YAML-Snippet vor (siehe BILL-15).
  * Stakeholder-Sign-off dokumentiert.
* **Aufwand**: S
* **Abhängigkeiten**: keine

---

### BILL-2 — DB-Migration `V7__billing_and_plans.sql`
* **Type**: Backend
* **Epic**: EPIC-1
* **Beschreibung**: Flyway-Migration anlegen (folgt `V{n}__`-Konvention).
* **Tabellen**:
  * `users` ALTER: Spalten `plan VARCHAR(16) NOT NULL DEFAULT 'FREE'`, `plan_status VARCHAR(32)`, `plan_valid_until TIMESTAMPTZ`, `stripe_customer_id VARCHAR(64) UNIQUE`, `billing_mode_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO'`.
  * `subscriptions` (id, user_id FK, stripe_subscription_id UNIQUE, plan, status, current_period_start, current_period_end, cancel_at_period_end BOOL, created_at, updated_at).
  * `payments` (id, user_id FK, stripe_payment_intent_id UNIQUE, amount_cents, currency, status, created_at).
  * `stripe_events` (id, stripe_event_id UNIQUE, type, payload JSONB, received_at, processed_at) – für Idempotenz.
  * `usage_events` (id, user_id FK, message_id FK NULLABLE, provider, model_id, prompt_tokens, completion_tokens, total_tokens, cost_micro_cents, billing_mode VARCHAR(16), tier_at_call VARCHAR(16), created_at).
  * `daily_quota_counters` (user_id, day DATE, managed_calls_count, PRIMARY KEY(user_id, day)).
* **Akzeptanz**:
  * `./gradlew flywayMigrate` (oder Boot-Start) läuft fehlerfrei gegen leere DB und gegen DB mit V6-Stand.
  * Indizes auf `usage_events(user_id, created_at)` und `subscriptions(user_id, status)`.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-1

---

### BILL-3 — Plan-Enum + User-Erweiterung
* **Type**: Backend
* **Epic**: EPIC-1
* **Beschreibung**: `Plan { FREE, PREMIUM, MAX }` Enum, `BillingMode { BYOK, MANAGED, AUTO }` Enum. `User` JPA-Entity um neue Spalten erweitern. Default `FREE`.
* **Akzeptanz**:
  * Bestehende Tests grün.
  * Neue Felder werden bei `CustomOAuth2UserService`-Erstanlage korrekt initialisiert.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-2

---

### BILL-4 — `EntitlementService` (Plan-Gating + Quotas)
* **Type**: Backend
* **Epic**: EPIC-1
* **Beschreibung**: Zentraler Service, der pro `userId` ein `Entitlements`-Record liefert: erlaubte Modelle, Attachment-Limits, Context-Limit, Tools (Brave-Search), Daily-Cap, parallele Streams. Konfiguration via `@ConfigurationProperties` `app.billing.tiers.<plan>.*` (folgt Repo-Konvention „keine Hardcodes").
* **API (intern)**:
  * `Entitlements get(userId)`
  * `void assertCanUseModel(userId, provider, modelId)` → wirft `PlanRestrictionException` (mappt im `GlobalExceptionHandler` zu HTTP 402/403).
  * `void assertWithinDailyCap(userId)`; `void incrementCounter(userId)` (atomarer UPSERT auf `daily_quota_counters`).
* **Akzeptanz**:
  * Unit-Tests pro Tier (Free/Premium/Max) mit `@ExtendWith(MockitoExtension.class)`.
  * Konfig im `application.yaml` unter `app.billing.tiers.{free,premium,max}.{models,maxAttachments,...}`.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-3

---

### BILL-5 — `ChatService`-Integration (Pre-Flight + Post-Call Accounting)
* **Type**: Backend
* **Epic**: EPIC-1
* **Beschreibung**:
  * Vor Provider-Call: `entitlementService.assertCanUseModel(...)` und `assertWithinDailyCap(...)` (nur bei Managed-Mode).
  * Nach Call (auch bei Stream-Ende/Abbruch im finally): `usageEventService.record(...)` und `entitlementService.incrementCounter(...)`.
  * Bei Provider-Fehler kein Counter-Increment (oder nachträglich kompensieren).
* **Akzeptanz**:
  * Bestehende `ChatServiceTest`-Tests grün.
  * Neue Tests: Free-User mit Premium-Modell → 403/402; Free-User über Daily-Cap → 429.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-4

---

### BILL-6 — `BillingMode`-Resolver in `DefaultProviderClientFactory`
* **Type**: Backend
* **Epic**: EPIC-3 / EPIC-4
* **Beschreibung**: Neue Komponente `ResolvedKey resolve(userId, provider, modelId, requestedMode)`:
  1. Wenn `requestedMode=BYOK` und User-Key existiert → BYOK.
  2. Wenn `requestedMode=AUTO`: bevorzugt BYOK falls Key vorhanden, sonst MANAGED falls Plan/Modell erlaubt.
  3. Wenn `requestedMode=MANAGED`: Server-Env-Keys (`OPENAI_API_KEY`, `GOOGLE_API_KEY`, `CLAUDE_API_KEY`, neu `OPENROUTER_API_KEY`).
  4. Sonst → `PlanRestrictionException`.
* Caffeine-Cache-Key in `DefaultProviderClientFactory` um `BillingMode` erweitern (User-Keys ≠ Server-Keys).
* **Akzeptanz**:
  * Unit-Tests für alle Pfade.
  * Bestehender BYOK-Pfad unverändert.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-4

---

### BILL-7 — OpenRouter Managed-Mode für Free Tier (`:free`-Modelle)
* **Type**: Backend
* **Epic**: EPIC-3
* **Beschreibung**: OpenRouter ist bereits als Provider integriert (siehe `application.yaml` `app.ai.openrouter.*`). Erweiterung:
  * Server-`OPENROUTER_API_KEY` als Secret.
  * `ModelFetchService` filtert Modelle mit Suffix `:free` für den Free-Tier (Whitelist im YAML als Fallback).
  * `EntitlementService.allowedModels(FREE)` = nur `openrouter/*:free`.
  * Provider-Header `HTTP-Referer` und `X-Title` setzen (OpenRouter-Anforderung) – konfigurierbar in `app.ai.openrouter.app-name`, `app.ai.openrouter.referrer`.
* **Akzeptanz**:
  * Free-User kann ohne BYOK chatten – nur mit OpenRouter-Free-Modellen.
  * Free-User mit GPT-5.2 → 403 mit Upgrade-Hint.
  * Integrationstest gegen Mock-Server.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-6

---

### BILL-8 — Premium-Modell-Whitelist (Server-Keys)
* **Type**: Backend
* **Epic**: EPIC-4
* **Beschreibung**: YAML-Whitelist `app.billing.tiers.premium.models`. Standard:
  * OpenAI: `gpt-5.2-mini`, `gpt-5.2-nano`, `gpt-4.1-mini-2025-04-14`, `gpt-4.1-2025-04-14`, `o4-mini-2025-04-16`.
  * Google: `gemini-3-flash`, `gemini-3.1-flash-lite-preview`, `gemini-2.5-flash-preview-05-20`.
  * Anthropic: `claude-sonnet-4-6-20260215`, `claude-haiku-4-5-20251001`.
  * OpenRouter: `openrouter/auto`.
* Nutzung über Server-`*_API_KEY` Secrets.
* **Akzeptanz**:
  * Premium-User kann alle Whitelist-Modelle ohne BYOK nutzen.
  * Premium-User mit `claude-opus-4-7` → 403/Upgrade.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-6, BILL-4

---

### BILL-9 — Max-Modell-Whitelist (inkl. Opus 4.7, GPT-5.2, Gemini 3.1 Pro, o3)
* **Type**: Backend
* **Epic**: EPIC-4
* **Beschreibung**: YAML-Whitelist `app.billing.tiers.max.models` = Premium-Set ∪ Top-Tier:
  * OpenAI: `gpt-5.2`, `o3-2025-04-16`.
  * Google: `gemini-3.1-pro-preview`, `gemini-3-pro`, `gemini-2.5-pro-preview-05-06`.
  * Anthropic: `claude-opus-4-7-*` (sobald Modell-ID feststeht; bis dahin `claude-opus-4-6-20260215`).
* **Akzeptanz**:
  * Max-User kann alle Modelle nutzen (alle drei Tiers).
* **Aufwand**: S
* **Abhängigkeiten**: BILL-8

---

### BILL-10 — Stripe-Konto, Produkte & Preise einrichten
* **Type**: DevOps / Setup
* **Epic**: EPIC-2
* **Beschreibung**: Stripe-Account aufsetzen (Live + Test), Produkte „chat-sribiddi Premium" und „chat-sribiddi Max", monatliche und jährliche Preise (mit Rabatt), USt. via Stripe Tax. Webhook-Endpoint registrieren. Test-API-Keys + Webhook-Secret in Vault.
* **Akzeptanz**:
  * Stripe-Dashboard zeigt beide Produkte mit korrekten Preisen.
  * Test-Mode-Checkout funktioniert manuell.
  * Stripe Tax aktiv für DE/EU-OSS.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-1

---

### BILL-11 — `BillingController` + Stripe Checkout-Session
* **Type**: Backend
* **Epic**: EPIC-2
* **Endpoints** (ohne `/api`-Prefix gem. Repo-Konvention):
  * `POST /billing/checkout-session` body `{plan: PREMIUM|MAX, interval: MONTH|YEAR}` → `{url}`.
  * `POST /billing/portal-session` → `{url}` (Stripe Customer Portal).
  * `GET /billing/me` → `{plan, status, periodEnd, cancelAtPeriodEnd, billingModePreference}`.
  * `GET /billing/usage` → letzten 30 Tage usage_events aggregiert.
* **Tech**: Stripe Java SDK; `@ConfigurationProperties` `app.billing.stripe.{secretKey, webhookSecret, priceIdPremiumMonth, ...}`.
* **Akzeptanz**:
  * E2E Manual-Test: Login → Checkout → Stripe Test-Card → Webhook flippt Plan → `GET /billing/me` zeigt Premium.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-3, BILL-10

---

### BILL-12 — Stripe Webhook (`POST /billing/webhook`)
* **Type**: Backend
* **Epic**: EPIC-2
* **Beschreibung**: Endpoint außerhalb von CSRF/Auth (in `SecurityConfig.securityFilterChain` `permitAll()` und `csrf.ignoringRequestMatchers(...)`). Signaturprüfung via `Stripe.Webhook.constructEvent`. Idempotenz über `stripe_events.stripe_event_id`.
* **Verarbeitete Events**:
  * `checkout.session.completed` → User-Plan setzen, `subscriptions`-Insert.
  * `customer.subscription.updated` → Plan/Status/PeriodEnd updaten.
  * `customer.subscription.deleted` → Plan auf `FREE` zurück (am `current_period_end`).
  * `invoice.paid` / `invoice.payment_failed` → `payments`-Insert; bei Failure Plan-Status auf `past_due`.
  * `charge.refunded` → optional Plan-Downgrade.
* **Akzeptanz**:
  * Replay desselben Events → kein Doppel-Effect.
  * Falsche Signatur → 400.
  * Integrationstest mit Stripe-CLI.
* **Aufwand**: M
* **Abhängigkeiten**: BILL-11

---

### BILL-13 — Plan-Lifecycle Job (Downgrade bei Ablauf)
* **Type**: Backend
* **Epic**: EPIC-2
* **Beschreibung**: Scheduled Job (`@Scheduled(cron = "0 5 * * * *")`) der User mit `plan_valid_until < now()` und `plan != FREE` auf `FREE` zurücksetzt (Failsafe falls Webhook ausfällt).
* **Akzeptanz**:
  * Test mit zurückdatiertem `plan_valid_until` → Plan wird `FREE`.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-12

---

### BILL-14 — Refund-Pfad (Provider-Fehler im Managed-Mode)
* **Type**: Backend
* **Epic**: EPIC-8
* **Beschreibung**: Wenn ein Managed-Call serverseitig fehlschlägt (Provider-5xx, Timeout), wird der Daily-Counter dekrementiert und kein `usage_events`-Eintrag mit Cost geschrieben (oder mit Cost=0 + Status=FAILED).
* **Akzeptanz**:
  * Mock-Provider 500 → Counter unverändert; Logeintrag.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-5

---

### BILL-15 — Modell-Catalog Filter pro Plan
* **Type**: Backend
* **Epic**: EPIC-1
* **Beschreibung**: `ModelController` (`GET /models`) liefert künftig pro angemeldetem User eine gefilterte Liste mit Flags:
  * `availability: ALWAYS | BYOK_ONLY | UPGRADE_REQUIRED`
  * `requiredPlan: PREMIUM | MAX | null`
* Free-User sieht alle Modelle, aber Premium/Max-Modelle als „🔒 Premium" mit Upgrade-Link.
* **Akzeptanz**:
  * Snapshot-Test pro Tier.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-4, BILL-7..9

---

### ADS-1 — Ad-Provider auswählen + DPA
* **Type**: Spike
* **Epic**: EPIC-5
* **Beschreibung**: Bewerten: Google AdSense, Carbon Ads, EthicalAds, Kevel. Kriterien: DSGVO-Konformität, kontextuell vs. behavioral, CPM, UI-Verträglichkeit (kein Layout-Shift), Server-Side-Option.
* **Empfehlung im Vorfeld**: Carbon Ads / EthicalAds (kontextuell, kein Cookie-Consent für IAB-TCF nötig) → niedrigerer CPM, aber DSGVO-arm. Fallback: Google AdSense mit Consent-Mode v2.
* **Akzeptanz**: Entscheidung dokumentiert + DPA unterschrieben.
* **Aufwand**: S

---

### ADS-2 — Ad-Slot-Komponenten im Frontend
* **Type**: Frontend
* **Epic**: EPIC-5
* **Beschreibung**:
  * Neue Component `<AdSlot :placement="..." />`.
  * Placements: `chat-sidebar-bottom`, `between-messages-every-n` (n konfigurierbar, z.B. nach 10 Messages), `settings-page-banner`.
  * Nur gerendert wenn `entitlements.showAds === true` (also nur Free).
  * Layout-stabil (festes `min-height` reservieren), kein CLS.
  * Respektiert `prefers-reduced-motion`.
* **Akzeptanz**:
  * Premium/Max-Login: keine Werbung im DOM.
  * Vitest-Tests für Conditional-Render.
* **Aufwand**: M
* **Abhängigkeiten**: ADS-1, BILL-21

---

### ADS-3 — Cookie-Banner / Consent-Mode
* **Type**: Frontend / Compliance
* **Epic**: EPIC-5 / EPIC-7
* **Beschreibung**: TCF-konformes Consent-Banner (z.B. Klaro!, Cookiebot, Usercentrics). Nur bei Werbung mit personenbezogenen Daten. Bei kontextuellen Ads (Carbon/Ethical) entfällt Banner-Pflicht für Ads – aber für OAuth/Stripe weiterhin Hinweis nötig.
* **Akzeptanz**:
  * Erst-Besuch: Banner; bei „Ablehnen" keine Ad-Skripte geladen.
  * Cookie-Setting jederzeit über Footer-Link aufrufbar.
* **Aufwand**: M
* **Abhängigkeiten**: ADS-1

---

### ADS-4 — Ads-Frequency-Cap & Anti-Annoyance
* **Type**: Frontend
* **Epic**: EPIC-5
* **Beschreibung**: Maximal 1 Ad pro Viewport, max. 1 Refresh / 60 s, kein Auto-Play-Audio/Video, niemals während aktivem Streaming.
* **Akzeptanz**:
  * E2E-Test: Streaming aktiv → keine Ad-Render-Calls.
* **Aufwand**: S

---

### ADS-5 — Ad-Block-Detect (sanft, kein Lockout)
* **Type**: Frontend
* **Epic**: EPIC-5
* **Beschreibung**: Wenn Ad-Block erkannt → freundlicher Hinweis-Banner („Wir finanzieren uns durch Werbung – oder werde Premium"), aber Funktion bleibt voll erhalten.
* **Akzeptanz**:
  * Hinweis dismissible, Frequency: max. 1× pro Session.
* **Aufwand**: S

---

### FE-1 — `useEntitlements` Composable + `usePlan`
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: `useEntitlements()` lädt `GET /billing/me` einmalig pro Session via `useChatApi().requestJson()` (Repo-Konvention!), exposed als `useState<Entitlements>('entitlements', ...)`. Liefert reactive `plan`, `showAds`, `allowedModels`, `caps`.
* **Akzeptanz**:
  * Vitest-Test in `app/composables/__tests__/useEntitlements.test.ts`.
* **Aufwand**: S

---

### FE-2 — `useBillingApi` Composable
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: `startCheckout(plan, interval)`, `openPortal()`, `getMe()`, `getUsage()`. Strikt über `requestJson` (keine raw `$fetch`).
* **Aufwand**: S
* **Abhängigkeiten**: BILL-11

---

### FE-3 — `pages/pricing.vue` (öffentlich)
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Drei-Spalten-Pricing-Vergleich, monatlich/jährlich Toggle. CTA „Kostenlos starten" / „Premium upgraden" / „Max upgraden". Layout über `auth`-Layout-Wrapper-Stil, aber öffentliche Route (kein Login-Zwang). Nutzt Tailwind-Theme-Palette des Repos (kein neues Farbpaletten-Hex).
* **Akzeptanz**:
  * Mobile-responsive.
  * SEO-Tags (`useSeoMeta`).
* **Aufwand**: M
* **Abhängigkeiten**: BILL-1

---

### FE-4 — `pages/billing.vue` (eingeloggt)
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Zeigt aktuellen Plan, Erneuerungsdatum, Cancel-Status, Button „Plan verwalten" → öffnet Stripe-Portal in neuem Tab. Sektion „Verbrauch (30 Tage)" basierend auf `GET /billing/usage`. Sektion „Tier-Limits" zeigt aktuelle Caps.
* **Akzeptanz**:
  * Free-User: CTA „Premium upgraden" sichtbar.
  * Premium-User: CTA „Auf Max upgraden".
* **Aufwand**: M
* **Abhängigkeiten**: FE-2

---

### FE-5 — Modell-Auswahl: gesperrte Modelle anzeigen
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Im Chat-Header / Modell-Picker: Modelle, die der aktuelle Plan nicht erlaubt, mit Schloss-Icon, Hover-Tooltip „Premium erforderlich" und Klick → Pricing-Page.
* **Akzeptanz**:
  * Free-User sieht GPT-5.2 als 🔒, Klick navigiert zu `/pricing`.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-15

---

### FE-6 — Up-Sell-Banner bei Quota-Limits
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Wenn Backend 402/429 mit `code: QUOTA_EXCEEDED` antwortet → Banner über Chat-Eingabe „Tageslimit erreicht – jetzt upgraden". Dismissible bis Mitternacht.
* **Akzeptanz**:
  * E2E manuell mit getoasterter Quote.
* **Aufwand**: S

---

### FE-7 — Plan-Badge in Sidebar
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Kleines Badge neben dem User-Avatar in `ChatSidebar.vue` (Free / Premium / Max), Klick → `/billing`.
* **Akzeptanz**:
  * Visuell konsistent mit Tailwind-Theme.
* **Aufwand**: XS

---

### FE-8 — `settings.vue`: BillingMode-Preference
* **Type**: Frontend
* **Epic**: EPIC-6
* **Beschreibung**: Toggle „Bevorzugter Modus": AUTO / BYOK only / Managed only. Persistiert via `PUT /users/me/settings`.
* **Akzeptanz**:
  * Setting wirkt sich auf nächsten Chat-Call aus.
* **Aufwand**: S
* **Abhängigkeiten**: BILL-3

---

### LEGAL-1 — AGB & Widerrufsbelehrung
* **Type**: Legal / Content
* **Epic**: EPIC-7
* **Beschreibung**: AGB für Abo-Modell (Laufzeit, Kündigung, Preisanpassungen), Widerrufsbelehrung mit ausdrücklichem Hinweis auf Verzicht für sofort bereitgestellte digitale Inhalte.
* **Akzeptanz**:
  * Verlinkt im Footer + Checkout-Flow als Pflicht-Checkbox.
* **Aufwand**: M (extern: Anwalt-Review)

---

### LEGAL-2 — Datenschutzerklärung Update
* **Type**: Legal / Content
* **Epic**: EPIC-7
* **Beschreibung**: `pages/datenschutz.vue` ergänzen um:
  * Stripe (Zahlungsabwicklung, USA, SCC).
  * Ad-Provider (siehe ADS-1).
  * OpenRouter als zusätzlicher AI-Provider mit Subprocessor-Liste.
  * Cookie-Tabelle.
* **Akzeptanz**:
  * Anwalt-Review abgeschlossen.
* **Aufwand**: M

---

### LEGAL-3 — Impressum-Erweiterung
* **Type**: Legal
* **Epic**: EPIC-7
* **Beschreibung**: USt-ID, Verantwortlicher, Hinweis auf OS-Plattform der EU.
* **Aufwand**: XS

---

### OBS-1 — Metriken & Dashboards
* **Type**: Backend / DevOps
* **Epic**: EPIC-8
* **Beschreibung**: Micrometer-Counter:
  * `billing.checkout.started{plan}`, `billing.checkout.completed{plan}`.
  * `billing.subscription.active{plan}` Gauge.
  * `chat.calls{tier,provider,model,mode}` Counter.
  * `chat.quota.exceeded{tier}` Counter.
* Prometheus-Endpoint ist bereits exponiert (`management.endpoints.web.exposure.include`). Grafana-Dashboard JSON in `infra/`.
* **Aufwand**: S

---

### OBS-2 — Anti-Abuse Rate-Limiting
* **Type**: Backend
* **Epic**: EPIC-8
* **Beschreibung**: Bucket4j (oder Redis-basiert) pro User: Free 30 req/min, Premium 120 req/min, Max 300 req/min, Webhook 100 req/min by IP. Über Filter im Spring-Filter-Chain.
* **Akzeptanz**:
  * Lasttest zeigt Drosselung mit 429 + `Retry-After`.
* **Aufwand**: M

---

### OBS-3 — Audit-Log für Plan-Änderungen
* **Type**: Backend
* **Epic**: EPIC-8
* **Beschreibung**: Jede Plan-Änderung (manuell, Webhook, Lifecycle-Job) loggt Eintrag in `audit_log` (oder `application.log` mit Marker), inkl. Trigger.
* **Aufwand**: S

---

### QA-1 — End-to-End Happy-Path-Test
* **Type**: QA
* **Epic**: alle
* **Beschreibung**: Skript / Playwright-Run: Free-Login → OpenRouter-Free-Chat funktioniert → GPT-5.2 ist gesperrt → Upgrade auf Premium via Stripe Test-Card → GPT-5.2 funktioniert → Cancel → am Periodenende wieder Free.
* **Akzeptanz**:
  * Test ist im CI als optionaler Job (mit Stripe Test-Keys) lauffähig.
* **Aufwand**: M

---

### QA-2 — Lasttest Managed-Mode
* **Type**: QA
* **Epic**: EPIC-8
* **Beschreibung**: k6-Skript: 100 gleichzeitige Premium-User, je 5 Calls/min. Verifiziert Quota-Counter-Korrektheit unter Concurrency.
* **Aufwand**: M

---

## 5. Reihenfolge / Roadmap

1. **Sprint 1 (Foundation)**: BILL-1, BILL-2, BILL-3, BILL-4, FE-1.
2. **Sprint 2 (Free Tier Managed)**: BILL-6, BILL-7, BILL-15, FE-5.
3. **Sprint 3 (Stripe Premium)**: BILL-10, BILL-11, BILL-12, BILL-13, BILL-8, FE-2, FE-3, FE-4, LEGAL-1, LEGAL-2.
4. **Sprint 4 (Max Tier + Polish)**: BILL-9, BILL-14, FE-6, FE-7, FE-8, OBS-1, OBS-2.
5. **Sprint 5 (Ads)**: ADS-1, ADS-3, ADS-2, ADS-4, ADS-5.
6. **Sprint 6 (Härten)**: OBS-3, QA-1, QA-2, LEGAL-3, Doku-Refresh.

---

## 6. Offene Entscheidungen

* Jährlicher Rabatt-Prozentsatz? (Vorschlag: 2 Monate gratis = 16,7 %).
* Studenten-/Open-Source-Rabatt?
* Refund-Policy bei Mid-Period-Downgrade (Stripe-Default = anteilig vs. Credit).
* Welcher Ad-Provider final (ADS-1).
* Ob für Free-Tier ein zusätzlicher „Ohne Werbung gegen Mini-Beitrag (2,99 €/Monat)"-Tier angeboten werden soll.
* Anthropic-Modell-ID für Opus 4.7 sobald verfügbar (BILL-9 Platzhalter).

---

## 7. Risiken

* **Provider-Kosten-Überschuss** im Managed-Mode → Mitigation via Daily-Caps (BILL-4) + Hard-Limit pro User pro Tag in Euro.
* **Stripe-Webhook-Ausfall** → Fallback Lifecycle-Job (BILL-13) + Stripe Dashboard Monitoring.
* **Ad-Revenue niedriger als erwartet** → Premium-Push priorisieren; Pricing-Page A/B-Test.
* **Rechts-/DSGVO-Risiko Werbung** → kontextuelle Ads bevorzugen (ADS-1).
* **Free-Abuse** (Multi-Account für Free-Quota) → Email-Verifizierung + Device-Fingerprint (Future-Ticket).
