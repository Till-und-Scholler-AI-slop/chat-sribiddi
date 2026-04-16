# Contributing to chat-sribiddi

This document captures the conventions and architectural decisions relevant for
further development. For a higher-level overview and build commands, see
`README.md` and `.github/copilot-instructions.md`.

---

## Branching

- Default branch: `main`
- Feature / polish work: `feature/<topic>`, `fix/<topic>`, `frontend-polish`, …
- Keep branches focused — prefer several small PRs over one large one.
- Commits must include the `Co-authored-by: Copilot` trailer when Copilot CLI
  helped author the change.

## Build & Test Commands

### Backend (Java 25 / Spring Boot 4)

```bash
cd Backend
./gradlew compileJava           # quick sanity check
./gradlew test                  # unit tests
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Frontend (Nuxt 4 / Vue 3)

```bash
cd Frontend/web
bun install                     # or: npm install
bun run dev                     # dev server
bun run build                   # production build
npx vitest run                  # tests (85 tests today)
npx nuxi prepare                # regenerate .nuxt/ tsconfig files (needed
                                # before the first vitest run in a fresh clone)
```

---

## Frontend Structure

```
Frontend/web/app/
  assets/css/main.css      Tailwind v4 theme + custom utilities
  components/
    ChatSidebar.vue        Chat list, new-chat, delete modal, footer nav
    MarkdownMessage.vue    Renders assistant markdown
    ToolCallDisplay.vue    Tool-call pill / payload view
    chat/
      ChatMessageReasoning.vue   Collapsible "Gedankengang" block
      ChatMessageFooter.vue      Token usage + cost + timestamp row
  composables/
    useChatApi.ts          Single API client (see below)
    useChatStore.ts        Reactive chat state + pure helpers
    useAuth.ts             Thin wrapper over useChatApi.checkSession()
    useUserSettings.ts     Persisted user settings (backend URL, theme)
    useKeyApi.ts           Per-user API-key CRUD
    __tests__/             Vitest suites for the composables
  middleware/auth.global.ts  Client-only redirect guard
  pages/
    index.vue              Redirects '/' → '/chat' (see OAuth note)
    chat.vue               Main chat view (monolithic — candidate for split)
    login.vue              Google OAuth entry
    settings.vue, models.vue, impressum.vue, datenschutz.vue
```

## Key Architectural Decisions

### 1. Single API client (`useChatApi`)

All backend requests go through `useChatApi.requestJson<T>()`. It:

- resolves the backend base URL via `normalizeBaseUrl()` /
  `DEFAULT_BACKEND_BASE_URL` — **never hardcode `http://localhost:8080`**;
- attaches `X-XSRF-TOKEN` automatically for mutating methods (POST/PUT/PATCH/DELETE);
- attaches `X-Chat-User-Id` on the client;
- throws on non-OK responses.

For requests that should *not* throw (session probing), use the
`checkSession()` method — it returns a boolean and never throws. `useAuth`
is implemented on top of it.

**Do not call `fetch`/`$fetch` directly from components or other composables.**

### 2. Chat state (`useChatStore`)

- Single source of truth for conversations, messages, tool calls.
- Uses `useState<T>(key, init)` (Nuxt's SSR-safe primitive) for shared
  reactive state.
- Pure helpers (`extractAssistantText`, `parseAttachmentContext`,
  `calculateMessageCost`, `estimatePreSendCost`, `formatCost`,
  `formatRatePerMillion`, `filterToolCallLogs`, `estimateConversationTokens`,
  …) are exported from `useChatStore.ts` and covered by Vitest.
- Store is ~1100 lines. A full split into sub-composables is risky because
  tests import helpers from here. When extracting, **re-export from
  `useChatStore.ts` for backward compat** so tests keep working.

### 3. Nuxt UI 4 + Tailwind v4 theme

- Palette is declared via `@theme` in `assets/css/main.css`. `--color-gray-*`
  is neutral slate — Nuxt UI semantic tokens (`bg-elevated`, `text-muted`,
  …) depend on this scale. Changing it will visibly affect every UI component.
- Primary accent: magenta `#B82E8E` (light) / `#E5338A` (dark). Access via
  `color="primary"` on Nuxt UI components or `var(--ui-primary)` in CSS.
- Bubbles:
  - `.msg-user` — magenta gradient + subtle shadow (no backdrop).
  - `.msg-assistant` — transparent background with a 2px left magenta accent.
- **`<UChatMessages>` pitfall**: its default `user.variant` is `'soft'`,
  which paints `bg-elevated/50` *behind* our `.msg-user` div (visible as a
  stray halo). Always pass `:user="{ variant: 'naked' }"` and
  `:assistant="{ variant: 'naked' }"` when using `<UChatMessages>`.

### 4. OAuth redirect flow

`Backend/src/main/java/com/rft/chat_sribiddi/auth/config/SecurityConfig.java`
uses `SimpleUrlAuthenticationSuccessHandler(frontendUrl)` → after Google
login the browser lands on `/`. `pages/index.vue` therefore does a
**client-side** `navigateTo('/chat', { replace: true })`. Do not remove
that redirect; do not replace it with SSR-side logic (middleware is
client-only and the root page would otherwise render empty).

### 5. CSRF

`SecurityConfig` uses `CookieCsrfTokenRepository.withHttpOnlyFalse()`, so the
CSRF cookie is readable from JS. `useChatApi.requestJson` reads it and sets
the `X-XSRF-TOKEN` header for mutating requests. Do not disable this without
updating the backend.

### 6. Auth middleware

`app/middleware/auth.global.ts` runs only on the client
(`if (import.meta.server) return`). Allowlist: `/`, `/login`, `/impressum`,
`/datenschutz`. Any new public page must be added there.

### 7. I18n / language

The UI is German. Toast titles, aria-labels, and prompt placeholders all
use German. When adding strings, keep them German and consistent in tone
(du-Form, short sentences). If i18n support becomes a real requirement,
introduce `@nuxtjs/i18n` rather than scattering ad-hoc translations.

### 8. Confirm dialogs

Never use `window.confirm` — it breaks the styled UI. Use a Nuxt UI
`<UModal>` bound to a reactive `ref` (see `ChatSidebar.vue` for the
delete-chat pattern).

### 9. Navigation

For internal navigation on `UButton`, use the `to="/path"` prop — Nuxt UI
renders a `NuxtLink` automatically (correct prefetching, no full reload).
Only use programmatic `navigateTo()` when the target depends on runtime
state.

---

## Polish Backlog (status after `frontend-polish`)

The following items were identified during the polish audit. Completed items
landed on `frontend-polish`; remaining items are good first tasks.

### ✅ Done

1. `pages/index.vue` redirects to `/chat` instead of rendering empty.
2. Removed user bubble "halo" by setting `variant: 'naked'` on `UChatMessages`.
3. Tightened `.msg-user` / `.msg-assistant` CSS (shadow, accent bar, spacing).
4. Redesigned `ToolCallDisplay.vue` (icon badge, per-call status, scrollable
   payloads, raw JSON behind toggle, German labels).
5. Deleted dead `pages/home.vue`.
6. `useAuth` now delegates to `useChatApi.checkSession()` — no more direct
   `fetch` + manual URL handling.
7. Chat deletion confirmation uses `<UModal>` instead of `window.confirm`.
8. Sidebar footer navigation uses `to="..."` on `UButton` (NuxtLink).
9. German i18n pass in `ChatSidebar.vue`, `chat.vue`, `login.vue`.
10. Reverted gray palette to neutral slate (was blue-tinted, clashed with
    Nuxt UI's semantic tokens).
11. Extracted `ChatMessageReasoning` and `ChatMessageFooter` from the
    `chat.vue` content slot.
12. Removed dead `runtimeConfig.public.userId` config and the matching
    `configuredUserId` fallback in `useChatApi.resolveUserId`.
13. Extracted `ChatWelcomeHero.vue` and `ChatMessageEditor.vue` from
    `chat.vue`; welcome prompt suggestions now auto-submit via
    `applyAndSubmit`.
14. Extracted pure helpers from `useChatStore.ts` into
    `composables/chat-helpers.ts`; `useChatStore` re-exports the public
    helpers and types so existing imports (and tests) keep working.
15. Added `utils/errors.ts` with `friendlyErrorMessage()` and applied it
    to all toast descriptions in `chat.vue` and `ChatSidebar.vue` (German
    copy, including 401/403/404/413/429/5xx and API-key errors).
16. Added `aria-label`s on the collapsed sidebar nav (`Modelle`,
    `Einstellungen`) and translated remaining English strings in
    `settings.vue` and `models.vue`.
17. ESLint set up via `@nuxt/eslint` (flat config in `eslint.config.mjs`).
    `npm run lint` / `npm run lint:fix` available; baseline is 0 errors,
    pre-existing patterns (dynamic-delete, invalid-void, v-for key alias)
    tracked as warnings.

### 🔜 Next up

- **Light-mode contrast audit**. After the palette revert, spot-check
  `UCard`, `UButton variant="soft"`, `UBadge`, and `UAlert` in both modes.
  (Manual visual task — no code change required upfront.)
- **Lint warning cleanup** (optional follow-up). `npm run lint` currently
  reports 59 warnings. The high-value targets:
    - `useChatStore.ts`: replace `delete obj[dynKey]` with rest-spread
      assignments (`{ [dynKey]: _, ...rest } = obj`) to satisfy
      `@typescript-eslint/no-dynamic-delete`.
    - API composables: narrow `Promise<void | T>` unions to
      `Promise<T | undefined>` for `no-invalid-void-type`.
    - `chat.vue`: two `v-for="x in [computed]" :key="message.id"` alias
      patterns inside `UChatMessages` slots — refactor to a computed.
- **Prettier / formatter**. ESLint is installed but no formatter is
  wired. Optional: add `@nuxt/eslint` stylistic rules or plain Prettier.
- **CI step**. Wire `npm run lint` and `npm run test` into the frontend
  CI pipeline (currently only backend is linted/tested in CI).

---

## Testing Guidelines

- **Backend**: JUnit 5 + Mockito. Prefer pure unit tests over
  `@SpringBootTest`. Tests mirror the main package layout.
- **Frontend**: Vitest. Tests live under
  `app/composables/__tests__/` and mirror the composable. Only test pure
  helpers and parsing logic — do not test Nuxt/Vue reactivity internals.
- Run `npx nuxi prepare` once in a fresh clone so `.nuxt/tsconfig*.json`
  exists before `npx vitest run`.

## Git hygiene

- Always include the trailer:
  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```
- Keep commit messages imperative and present-tense
  (`fix`, `refactor`, `docs`, …).
- Do not commit `.output/`, `.nuxt/`, `node_modules/`, or any file
  containing secrets.
