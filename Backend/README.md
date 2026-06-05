# Backend

TypeScript/Hono backend for Chat Sribiddi. It stores chats in PostgreSQL, streams
AI responses over SSE, manages encrypted per-user provider credentials, exposes
the existing frontend-compatible API surface, and keeps Flyway SQL migrations as
the only schema source.

## Quick Start

1. Prereqs: Node.js 22, Docker, `openssl` for production secrets.
2. Install dependencies:
   ```sh
   npm ci
   ```
3. Copy `.env.example` to `.env` and set at minimum:
   - `POSTGRES_USER`, `POSTGRES_PASSWORD`
   - `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` for login
   - `ALLOWED_ORIGINS=http://localhost:3000`
   - `ENCRYPTION_SECRET=$(openssl rand -base64 32)` for production
4. Start local dependencies and Flyway:
   ```sh
   docker compose up -d db redis
   docker compose run --rm flyway
   ```
5. Run the backend:
   ```sh
   npm run dev
   ```

The app listens on `PORT` (default `8080`) and exposes health at
`/actuator/health`. OpenAPI JSON is emitted at `/openapi.json`.

## Scripts

- `npm run dev` - Hono dev server through `tsx watch`
- `npm run build` - compile TypeScript to `dist`
- `npm start` - run `dist/index.js`
- `npm run lint` - Biome check
- `npm run format` - Biome safe write
- `npm test` - Vitest, including a Testcontainers PostgreSQL smoke test
- `npm run db:migrate` - run the Compose Flyway service

## Architecture

- `src/routes` parses HTTP requests and delegates only.
- `src/services` contains application/business logic.
- `src/use-cases` contains cross-service orchestration.
- `src/repositories` owns Kysely queries.
- `src/db` owns Kysely/node-postgres setup and DB table typings.
- `src/config`, `src/errors`, and `src/middleware` hold runtime config,
  error mapping, CORS/CSRF/rate-limit/request context.

Route handlers should not contain business logic.

## Compatibility Surface

Preserved endpoint families:

- Auth/session: `/secured`, `/oauth2/authorization/google`,
  `/login/oauth2/code/google`, `/logout`
- Account/settings: `/account`, `/account/settings`, `/settings`
- API keys: `/apikeys/{provider}`, `/apikeys/{provider}/exists`
- Provider connections: `/provider-connections/**`, `/providers`
- Models: `/models`, `/models/openai`, `/models/google`, `/models/claude`,
  `/models/openrouter`
- Chat: `/chats/**`, `/messages/{messageId}/tool-calls`
- Guest chat: `/anonymous/chats/stream`
- Usage: `/usage`

Frontend DTOs remain camelCase. PostgreSQL tables and Flyway migrations remain
under `src/main/resources/db/migration`.

Document upload supports text-like files and PDF extraction for chat RAG
context. Other binary document formats are rejected until explicit extractors
are added.

## Migration Notes

See [`MIGRATION_NOTES.md`](./MIGRATION_NOTES.md) for intentional behavior
changes and parity gaps that remain after the Hono migration.

## Auth and CSRF

Production login uses Google OAuth and signed Hono cookies. Mutating requests
from a signed-in browser session must send the `XSRF-TOKEN` cookie value as
`X-XSRF-TOKEN`. In `development`, `dev`, and `test`, the backend also accepts
`X-Chat-User-Id` as a local/dev user id.

## Per-User Provider Credentials

- Store/replace legacy provider key: `PUT /apikeys/{provider}` body
  `{ "apiKey": "..." }`
- Delete: `DELETE /apikeys/{provider}`
- Exists check: `GET /apikeys/{provider}/exists`
- New provider catalog credentials use `/provider-connections/{providerId}/api-key`.
- OpenRouter PKCE and Google provider OAuth use
  `/provider-connections/{providerId}/connect` and direct callbacks at
  `/provider-connections/{providerId}/callback`.

Credentials are encrypted at rest with versioned AES-GCM ciphertext compatible
with the migrated keyring format. Rotate with:

- `ENCRYPTION_ACTIVE_KEY_VERSION`
- `ENCRYPTION_DECRYPTION_SECRETS=1:<base64>,2:<base64>`

## Migrations

Flyway is the schema owner. Do not add a TypeScript migration system. New schema
changes must be SQL files in `src/main/resources/db/migration/V{n}__*.sql`.
Docker Compose runs `flyway/flyway` before the backend container starts.

## Docker

Local:

```sh
docker compose up -d --build
```

Production uses `deploy/docker-compose.prod.yml`. It starts Postgres, Redis,
Flyway, then the Hono backend built from `Dockerfile.prod`.
