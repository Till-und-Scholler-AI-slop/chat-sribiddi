# Backend — Hono / TypeScript / Kysely

## Overview

TypeScript backend for multi-model AI chat with BYOK credential access, guest
OpenRouter Free, SSE streaming, encrypted provider credentials, PostgreSQL, and
Redis-backed quota/rate limiting. Hono is the active runtime.

## Active Structure

```
src/
├── app.ts              # Hono app composition
├── index.ts            # Node server entrypoint
├── config/             # Env parsing and runtime config
├── db/                 # Kysely/node-postgres setup and DB typings
├── errors/             # HttpError and global error mapping
├── middleware/         # Request id, CORS-adjacent auth checks, CSRF, rate limit
├── repositories/       # Kysely queries only
├── routes/             # Thin Hono request adapters
├── services/           # Business/application logic
├── use-cases/          # Cross-service orchestration
├── test-utils/         # Test helpers
└── main/resources/     # Flyway migrations only
```

## Conventions

- Node.js 22, ESM TypeScript.
- Hono route handlers parse/authorize/delegate/respond only. Do not put business
  logic in routes.
- Use Kysely repositories for DB access. Services/use-cases should not contain
  raw SQL unless there is a clear reason.
- Flyway SQL remains the only schema source:
  `src/main/resources/db/migration/V{n}__*.sql`.
- Frontend DTO compatibility matters. Keep response fields camelCase unless the
  frontend contract changes too.
- Use `X-Chat-User-Id` only in `development`, `dev`, and `test`.
- Keep `docs/operations.md` updated for deploy, env, auth/session, Caddy, and
  container behavior changes.

## Provider And Tool Notes

- Native chat providers use the Vercel AI SDK packages.
- OpenAI-compatible/local endpoint provider connections execute through the AI
  SDK OpenAI-compatible adapter.
- OpenRouter PKCE and Google provider OAuth are implemented. Other cataloged
  provider OAuth/device flows still need explicit implementation before they
  should be advertised as enabled.
- Brave Search is user-key only. There is no managed server Brave key.
- Calculator, current date/time, and URL fetcher are AI SDK model-side tools.
  Brave Search currently enriches the prompt and writes a tool-call log.
- Document upload extracts text-like files and PDFs into `document_chunks`.
  Unsupported binary formats are rejected unless a dedicated extractor is added.

## Commands

```bash
npm ci
npm run dev             # local Hono dev server
npm run build           # TypeScript build
npm run lint            # Biome check
npm test                # Vitest + Testcontainers
npm run db:migrate      # Docker Compose Flyway service
docker compose up -d    # db, redis, flyway, backend
```
