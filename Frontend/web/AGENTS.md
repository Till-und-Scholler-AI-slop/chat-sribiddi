# Frontend/web — Nuxt 4 / Vue 3 / TypeScript / Nuxt UI v4

## OVERVIEW
SPA frontend for AI chat platform. Nuxt 4 with file-based routing, Nuxt UI v4 components, Tailwind v4, i18n (de/en). No Pinia — state via `useState()` composables. Pure SPA (no `server/` directory), all API calls to separate Spring Boot backend.

## STRUCTURE
```
Frontend/web/
├── app/
│   ├── composables/    # 19 files: state stores + API clients (see composables/AGENTS.md)
│   ├── pages/          # 12 flat pages (no dynamic routes, no nesting)
│   ├── components/     # 11 total: ChatSidebar, ComparePanel, MarkdownMessage, ToolCallDisplay
│   │   ├── chat/       # ChatMessageEditor, ChatMessageFooter, ChatMessageReasoning, etc.
│   │   └── settings/   # ApiKeyCard
│   ├── layouts/        # default.vue, auth.vue (misnamed — content container), login.vue
│   ├── middleware/      # auth.global.ts (15 lines, hardcoded public page allowlist)
│   ├── utils/          # format.ts, model-utils.ts, errors.ts, markstreamCodeRenderer.ts
│   └── plugins/        # service-worker.client.ts (manual SW registration, no @vite-pwa/nuxt)
├── i18n/
│   ├── i18n.config.ts  # @nuxtjs/i18n config (all warnings suppressed)
│   └── locales/        # de.json (default, 32KB), en.json (29KB)
├── tests/e2e/          # Playwright specs + mock-backend.ts
├── nuxt.config.ts      # Nuxt config, Vite manual chunking, all font providers disabled
├── eslint.config.mjs   # Flat ESLint config (Nuxt module)
├── vitest.config.ts    # Unit tests
└── playwright.config.ts # E2E tests
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Chat state machine | `app/composables/useChatStore.ts` | 993 lines, god composable |
| API client + types | `app/composables/useChatApi.ts` | 684 lines, HTTP + 16 type exports |
| Auth flow | `app/composables/useAuth.ts` | Module-level dedup promise |
| Billing/entitlements | `app/composables/useEntitlements.ts` | 317 lines, SSR/client dual-context |
| Model catalog | `app/composables/useModelStore.ts` | Model list + favorites |
| Main chat page | `app/pages/chat.vue` | 911 lines, message editor + streaming UI |
| Settings page | `app/pages/settings.vue` | 860 lines, 5 tabs, repetitive provider cards |
| Markdown rendering | `app/utils/markstreamCodeRenderer.ts` | 226-line Shiki integration (stateful) |
| i18n strings | `i18n/locales/{de,en}.json` | `de` is source locale |
| E2E mock backend | `tests/e2e/mock-backend.ts` | Playwright route interceptor |

## CONVENTIONS
- **No Pinia** — all state via `useState()` in composables that call each other
- **Composable naming**: `use*` prefix for state/API composables; `chat-helpers.ts` and `chat-stream.ts` break this (utility files in composables dir)
- **Component naming**: PascalCase `.vue` files; subdirectories for `chat/` and `settings/` only
- **API calls**: All go through `useChatApi.requestJson<T>()` — never raw `fetch`/`$fetch` in components
- **CSRF**: `requestJson` auto-attaches `X-XSRF-TOKEN` header on mutating requests
- **Styling**: Tailwind v4 with custom `@theme` palette in `assets/css/main.css` — blue-navy gray scale, `#e0e1dd` body text, `--ui-primary` sky blue accent
- **i18n**: `t('namespace.key')` — never hardcode strings. Special chars: wrap `@` in quotes: `user{'@'}example.com`
- **Tests**: Vitest in `app/composables/__tests__/` (co-located), Playwright in `tests/e2e/`
- **Vitest mocks Nuxt globals manually** — `vi.stubGlobal('useState', ...)`, no `@nuxt/test-utils`

## ANTI-PATTERNS
- **`useChatStore.ts` (993 lines)** — god composable, split candidate
- **`useChatApi.ts` (684 lines)** — conflates type definitions with API client
- **Module-level mutable state** in `useAuth.ts` and `useIsMobile.ts` — shared singletons, SSR hazard
- **`chat-helpers.ts` and `chat-stream.ts`** in composables/ — not composables, defeat auto-import convention
- **`auth.vue` layout** — misnamed, used for pricing/legal pages (not auth flows)
- **`index.vue`** — redirect-only shell (`navigateTo('/chat')`), could be config-level redirect
- **`format.ts`** re-exports from `chat-helpers.ts` — circular dependency risk
- **`markstreamCodeRenderer.ts`** in utils/ — stateful Shiki renderer, belongs in plugin
- **i18n warnings all suppressed** — `missingWarn: false`, `fallbackWarn: false` masks missing translations
- **No `error.vue`** — no custom error layout
- **Hardcoded public page allowlist** in `auth.global.ts` middleware — brittle

## COMMANDS
```bash
npm run dev          # Dev server (http://localhost:3000)
npm run build        # Production build
npm run test         # Vitest unit tests
npm run test:e2e     # Playwright E2E (launches dev server)
npm run lint         # ESLint
```
