# Copilot Instructions for chat-sribiddi

## Project Overview

`chat-sribiddi` is a lightweight AI chat platform with a Nuxt web UI and a
TypeScript backend for multiple AI providers.

- **Backend**: Hono / TypeScript / Kysely REST and SSE API
- **Frontend**: Nuxt 4 / Vue 3 SPA written in TypeScript
- **Database**: PostgreSQL with Flyway SQL migrations
- **Auth**: Google OAuth in production, signed Hono cookies, dev header auth in
  local/test profiles

---

## Repository Layout

```
Backend/
  src/
    routes/          Thin Hono route adapters
    services/        Business and application logic
    use-cases/       Cross-service orchestration
    repositories/    Kysely queries
    db/              node-postgres/Kysely setup and table typings
    config/          Env parsing and model catalog fallback YAML
    middleware/      Request context, CSRF, rate limiting
    errors/          HttpError and global error mapping
    main/resources/db/migration/
                     Flyway SQL migrations

Frontend/web/
  app/
    composables/     useChatStore, useChatApi, useAuth, useKeyApi, useUserSettings
    components/      ChatSidebar.vue, MarkdownMessage.vue
    pages/           chat.vue, login.vue, home.vue, settings.vue, ...
    assets/css/      main.css (Tailwind v4 with custom @theme palette)
```

---

## Build & Test Commands

### Backend

```bash
cd Backend
npm ci
npm run lint
npm run build
npm test
npm run dev
docker compose run --rm flyway
```

### Frontend

```bash
cd Frontend/web
npm install
npm run dev
npm run build
npm test
```

---

## Backend Conventions

- Node.js 22, ESM TypeScript.
- Route handlers parse input, authorize, delegate, and respond only. Do not put
  business logic in Hono routes.
- Services and use-cases own application behavior.
- Repositories own Kysely queries. Avoid raw SQL outside repositories unless
  there is a clear reason.
- Flyway remains the only schema source. New migrations must be SQL files in
  `Backend/src/main/resources/db/migration/V{n}__description.sql`.
- Keep DTOs frontend-compatible and camelCase.
- Use `X-Chat-User-Id` only in `development`, `dev`, and `test`.
- Provider clients are created through the AI SDK provider packages or the
  OpenAI-compatible adapter.
- Fallback model entries live in `Backend/src/config/model-catalog.yaml`.

---

## Frontend Conventions

### Framework & Language

- **Nuxt 4** with **Vue 3** Composition API
- **TypeScript** throughout; all composables, components, and pages must be typed.
- **@nuxt/ui 4** for UI components; icons from `heroicons` and `lucide` via Iconify.

### State Management

- Use `useState<T>(key, init)` for shared reactive state inside composables.
- `useChatStore` is the source of truth for conversations and messages.
- `useChatApi` is the sole API client. Do not call `fetch`/`$fetch` directly in components.

### API Calls

- All backend requests go through `useChatApi.requestJson<T>()`.
- `requestJson` automatically attaches `X-XSRF-TOKEN` on mutating requests.
- Import `DEFAULT_BACKEND_BASE_URL` from `useChatApi.ts`; do not hardcode
  `http://localhost:8080`.

### Styling

- Tailwind CSS v4 with custom `@theme` palette in `assets/css/main.css`.
- Stick to the existing palette; do not introduce arbitrary colors casually.

### Testing

- Tests use Vitest.
- Test files live near the feature/composable they exercise.
- Focus on pure utility functions and response parsing; avoid testing internal
  Nuxt/Vue reactivity.
