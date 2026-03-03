# Backend

Spring Boot + Spring AI backend that stores chats, proxies to OpenAI, Google Gemini, or Claude, and manages per-user API keys.

## Quick start (local dev)
1) Prereqs: JDK 25, Docker, `openssl` (to generate secrets).
2) Copy `.env.example` to `.env` and set at minimum:
   - OAuth: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
   - Database: `POSTGRES_USER`, `POSTGRES_PASSWORD`
   - Model provider defaults (optional for dev now that keys are user-supplied): `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `CLAUDE_API_KEY`, `GOOGLE_PROJECT_ID`, `GOOGLE_LOCATION`
   - Optional OpenAI HTTP tuning for long reasoning requests: `OPENAI_CONNECT_TIMEOUT=10s`, `OPENAI_READ_TIMEOUT=180s`
   - CORS: `ALLOWED_ORIGINS=http://localhost:3000`
   - Encryption: `ENCRYPTION_SECRET=$(openssl rand -base64 32)`  # must decode to 32 bytes
   - Optional rotation settings:
     - `ENCRYPTION_ACTIVE_KEY_VERSION=1`
     - `ENCRYPTION_DECRYPTION_SECRETS=1:<base64>,2:<base64>`
3) Start Postgres: `docker compose up -d` (uses `compose.yaml`).
4) Run with dev profile (no auth, CSRF off): `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`
5) Run with production profile (OAuth2 + CSRF): `SPRING_PROFILES_ACTIVE=production ./gradlew bootRun`
6) Swagger UI: `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`).

## Running tests
`./gradlew test` (ensure `GRADLE_USER_HOME` points to a writable directory if your global `~/.gradle` is locked).

## Deployment checklist
- Set `SPRING_PROFILES_ACTIVE=production`.
- Provide real `ALLOWED_ORIGINS` (comma-separated patterns).
- Provide `ENCRYPTION_SECRET` (32-byte base64) and rotate periodically.
- If rotating keys, bump `ENCRYPTION_ACTIVE_KEY_VERSION` and keep old versions in `ENCRYPTION_DECRYPTION_SECRETS` until data is re-encrypted.
- Configure external Postgres; Flyway runs migrations at startup (`spring.flyway.baseline-on-migrate=true`, `ddl-auto=none`).
- Ensure HTTPS termination and secure cookies (CSRF tokens use cookies).
- Set provider defaults only if you want server-wide fallback keys; otherwise rely on user-stored keys.

## Authentication & CSRF
- Production profile: OAuth2 login (Google). Public endpoints: `/`, `/login`, `/swagger-ui/**`, `/v3/api-docs/**`. Everything else requires auth.
- CSRF: enabled with cookie tokens. SPA must read `XSRF-TOKEN` cookie and send it as `X-XSRF-TOKEN` on mutating requests.
- Dev profile: all requests permitted; CSRF disabled (dev only).

## Per-user API keys (persisted, encrypted)
- Store/replace: `PUT /apikeys/{provider}` body `{ "apiKey": "..." }`
- Delete: `DELETE /apikeys/{provider}`
- Exists check: `GET /apikeys/{provider}/exists`
- Chat calls may omit `apiKey`; backend will use the stored key for the authenticated user+provider.
- Keys are encrypted at rest via a JPA AttributeConverter backed by Spring Security crypto.
- Stored ciphertext is versioned (`v<version>:...`) and each row stores `key_version` for rotation tracking.
- Stored keys are automatically re-encrypted on read when `key_version` is older than `ENCRYPTION_ACTIVE_KEY_VERSION`.

## Chat API
- POST `/chats` — create chat. Body: `{"title": "optional"}`. `userId` is set from the authenticated principal.
- POST `/chats/{chatId}/messages` — send message.
  ```json
  {
    "content": "Hello!",
    "provider": "OPENAI" | "GOOGLE" | "CLAUDE",
    "modelId": "gpt-5.2-2025-12-11",
    "apiKey": "optional override",
    "media": [
      { "mimeType": "image/png", "data": "<base64-or-data-url>" }
    ]
  }
  ```
  Uses last 20 messages + system prompt, stores both user and assistant replies. For multimodal requests, attachments are only sent with the current user turn.
- DELETE `/chats/{chatId}` — deletes chat and its messages (per-user scoped).

## Model catalog
- GET `/models/openai`, `/models/google`, and `/models/claude` — list configured model options (`ai.models.*` in `application.yaml`).

## Data model
- `chats`: `chat_id` (UUID, PK), `title`, `created_at`, `user_id`
- `messages`: `id` (PK), `chat_id` (FK), `message` (TEXT), `context`, `role`, `created_at`
- `users`: `id`, `email` (unique), `provider_id`
- `api_keys`: `id`, `user_id`, `provider`, `encrypted_key`, `key_version`, timestamps (unique per user+provider)
- Indexes: `chats(user_id)`, `messages(chat_id, created_at desc)`

## Migrations
- Flyway enabled; initial schema in `src/main/resources/db/migration/V1__init.sql`.
- Hibernate `ddl-auto` is disabled in production (`none`).

## Logging
- Prompt/response bodies only logged at TRACE; default INFO avoids leaking content.

## CORS
- Configured via `app.cors.allowed-origins` (comma-separated patterns). Defaults to `http://localhost:3000` for dev.

## Notes
- If Gradle cannot write to `~/.gradle`, set `GRADLE_USER_HOME=/tmp/gradle-cache` (or another writable dir) before running builds or tests.
