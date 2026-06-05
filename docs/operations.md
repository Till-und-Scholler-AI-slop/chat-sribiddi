# Operations Runbook

This document is the repository-owned source of truth for production operations
that affect deployment, routing, OAuth login, and incident triage.

Do not store passwords, API keys, OAuth client secrets, or other credentials in
this file. Keep secrets in the relevant deployment secret store or on the VPS in
the deployment `.env` files.

## Production Topology

- Public domain: `https://germinai.de`
- Host reverse proxy: Caddy, configured by [`infra/caddy/Caddyfile`](../infra/caddy/Caddyfile)
- Frontend: Nuxt container exposed only on `127.0.0.1:3000`
- Backend: Spring Boot container exposed only on `127.0.0.1:8080`
- Backend dependencies: Postgres and Redis from [`Backend/deploy/docker-compose.prod.yml`](../Backend/deploy/docker-compose.prod.yml)
- Frontend deploy config: [`Frontend/web/deploy/docker-compose.prod.yml`](../Frontend/web/deploy/docker-compose.prod.yml)

Caddy routing conventions:

- `/api/*` is routed to the backend with `handle_path`, so the backend receives
  paths without the `/api` prefix.
- `/login/oauth2/*` is routed directly to the backend for the Google OAuth
  callback. This path is used by Spring Security's default redirect endpoint.
- `/oauth2/*` is routed directly to the backend as an optional non-`/api`
  authorization entrypoint.
- Everything else is routed to the Nuxt frontend.

Frontend/backend URL conventions:

- Production frontend must use `NUXT_PUBLIC_BACKEND_BASE_URL=/api` or the
  equivalent absolute same-origin value `https://germinai.de/api`.
- Backend controllers are registered without an `/api` prefix. The `/api`
  prefix is a Caddy public-path concern.
- OAuth login starts at `/api/oauth2/authorization/google` from the frontend and
  should redirect to Google with `redirect_uri=https://germinai.de/login/oauth2/code/google`.

Public/authenticated application paths:

- `/` redirects to `/chat`.
- `/chat`, legal pages, `/models/*`, and `/api/anonymous/chats/stream` are
  public. Anonymous chat uses only `OPENROUTER/openrouter/free` and stores chat
  history in browser localStorage.
- The backend must have `OPENROUTER_API_KEY` configured only for this guest
  OpenRouter Free path; otherwise anonymous calls return HTTP `503` with
  `MANAGED_PROVIDER_UNCONFIGURED`.
- Signed-in chat is BYOK-only. A user must store a provider API key in Settings
  before sending authenticated model requests for that provider; missing keys
  return `API_KEY_REQUIRED`.
- `/settings`, `/usage`, `/compare`, `/api/chats/import`, account endpoints,
  key endpoints, usage endpoints, and authenticated chat endpoints require a
  valid session.
- After a guest logs in, the frontend can call `POST /api/chats/import` to
  persist selected local guest chats without re-calling the AI provider.

Session lifetime:

- Browser login sessions are intentionally long-lived so normal users are not
  logged out automatically during regular use. The backend defaults both
  `SESSION_TIMEOUT` and `SESSION_COOKIE_MAX_AGE` to `30d`; production can
  override those env vars together if the policy changes.

Rate and quota defaults:

- Per-minute request limits are intentionally DDoS-oriented:
  anonymous `RATE_LIMIT_ANONYMOUS_PER_MINUTE=300` and authenticated
  `RATE_LIMIT_AUTHENTICATED_PER_MINUTE=600`.
- Anonymous chat has a separate daily guest OpenRouter Free cap,
  `ACCESS_GUEST_DAILY_CAP=20` by default. Production should keep Redis
  available because Redis-backed rate limiting and guest quota checks fail
  closed when Redis is configured but unavailable.

Tool configuration:

- Web search uses Brave Search only with the signed-in user's stored
  `BRAVE_SEARCH` key from Settings. There is no managed Brave Search key in
  production config.

## Repo-Owned Infra

Everything required to recreate production routing and container startup should
live in this repo:

- Caddy config: [`infra/caddy/Caddyfile`](../infra/caddy/Caddyfile)
- Backend production compose: [`Backend/deploy/docker-compose.prod.yml`](../Backend/deploy/docker-compose.prod.yml)
- Backend env template: [`Backend/deploy/.env.example`](../Backend/deploy/.env.example)
- Frontend production compose: [`Frontend/web/deploy/docker-compose.prod.yml`](../Frontend/web/deploy/docker-compose.prod.yml)
- Frontend env template: [`Frontend/web/deploy/.env.example`](../Frontend/web/deploy/.env.example)
- Deploy workflows:
  - [`.github/workflows/deploy-backend.yml`](../.github/workflows/deploy-backend.yml)
  - [`.github/workflows/deploy-frontend.yml`](../.github/workflows/deploy-frontend.yml)

If production Caddy, Docker Compose, ports, env var names, health checks, or
deployment paths are changed manually on the VPS, commit the matching change to
the repo in the same work session. The VPS should not become the only place
where operational state exists.

## Deploy Flow

Backend deploy:

1. `.github/workflows/deploy-backend.yml` validates `Backend` with Gradle.
2. The workflow builds `Backend/app.jar` on GitHub Actions, then uploads the
   backend bundle and `infra/caddy`.
3. On the VPS it writes `Backend/.env` from `BACKEND_ENV_FILE`.
4. Optional single GitHub secret `OPENROUTER_API_KEY` overrides the matching
   key in `Backend/.env` when it is set, so rotating the guest OpenRouter Free
   key does not require editing the whole multiline `BACKEND_ENV_FILE` secret.
5. It runs `docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build --remove-orphans`.
   The production compose file uses `Backend/Dockerfile.prod`, which copies the
   prebuilt JAR instead of running Gradle on the VPS.
6. It validates and installs the Caddyfile:
   - `caddy validate --config "$APP_DIR/infra/caddy/Caddyfile"`
   - `cp "$APP_DIR/infra/caddy/Caddyfile" /etc/caddy/Caddyfile`
   - `systemctl reload caddy || systemctl restart caddy`

Frontend deploy:

1. `.github/workflows/deploy-frontend.yml` validates `Frontend/web` with Bun.
2. The workflow uploads the frontend bundle.
3. On the VPS it writes `Frontend-web/.env` from `FRONTEND_ENV_FILE`.
4. It runs `docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build --remove-orphans`.

## Required Smoke Checks

Run these after changes to deploy, Caddy, auth, session handling, or container
startup:

```sh
curl -sS https://germinai.de/api/actuator/health
curl -sS -D - -o /tmp/germinai-secured.txt https://germinai.de/api/secured
curl -sS -D - -o /tmp/germinai-oauth.txt https://germinai.de/api/oauth2/authorization/google
curl -sS -D - -o /tmp/germinai-login.html https://germinai.de/login
curl -sS -D - -o /tmp/germinai-chat.html https://germinai.de/chat
```

Expected results:

- `/api/actuator/health`: HTTP `200`, body contains `"status":"UP"`.
- `/api/secured`: HTTP `401` without a session. This proves the backend is
  reachable and auth protection is active.
- `/api/oauth2/authorization/google`: HTTP `302` to `accounts.google.com`.
- `/login`: HTTP `200` from Nuxt.
- `/chat`: HTTP `200` from Nuxt without requiring a session.

For an end-to-end browser smoke check, an anonymous visitor should land on the
chat UI, see only the theme toggle plus Google login button in the sidebar
footer, send a message with `OpenRouter Free` without a key-required alert,
and a login button click should navigate to `accounts.google.com`.

Also smoke-check the signed-in BYOK path after backend deploys: with no stored
provider key, sending a non-guest model request should fail with
`API_KEY_REQUIRED`; after storing a valid provider key in Settings, the same
provider should stream successfully. The web-search tool should bind only after
a user stores a `BRAVE_SEARCH` key.

## VPS Triage Commands

Use owner-approved credentials only. Do not commit credentials or paste them
into docs.

```sh
ssh root@germinai.de
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}\t{{.Image}}'
docker inspect --format '{{.State.Status}} {{.State.Health.Status}} {{.RestartCount}}' chat_sribiddi_backend-backend-1
docker logs --tail=160 chat_sribiddi_backend-backend-1
docker logs --tail=160 chat_sribiddi_frontend-frontend-1
cmp -s /etc/caddy/Caddyfile /opt/chat-sribiddi/infra/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl is-active caddy
```

Useful interpretations:

- Caddy `502` on `/api/*` usually means the backend upstream on
  `127.0.0.1:8080` is down, unhealthy, or restarting.
- Caddy `200` on `/login` while `/api/actuator/health` returns `502` means the
  frontend is up but the backend is not reachable.
- A backend `RestartCount` greater than zero after deploy should be treated as a
  production incident until logs prove the container is stable.

## OAuth Login Troubleshooting

Chrome error:

```text
Unsafe attempt to load URL ... from frame with URL chrome-error://chromewebdata/.
Domains, protocols and ports must match.
```

This usually means the browser was already on a Chrome error page and then tried
to navigate to the OAuth URL from that failed frame. It is a symptom, not the
root cause. First verify that the backend OAuth entrypoint works:

```sh
curl -sS -D - -o /tmp/germinai-oauth.txt https://germinai.de/api/oauth2/authorization/google
```

Expected: HTTP `302` to `https://accounts.google.com/...`.

If it returns `502`, check the backend container health and logs. If it returns
`404`, check Caddy routing and whether `handle_path /api/*` is stripping the
public `/api` prefix correctly. If Google later rejects the redirect, verify the
OAuth client configuration includes:

```text
https://germinai.de/login/oauth2/code/google
```

## Spring Boot Production Startup Pitfalls

Production startup must be validated through CI/deploy, not only focused unit
tests. The following issues have previously broken the VPS:

- Spring AI 2.0.0-M7 uses the official OpenAI Java SDK for OpenAI-compatible
  chat requests. For OpenRouter, configure `OPENROUTER_BASE_URL` with the API
  root such as `https://openrouter.ai/api/v1`; there is no separate
  `OPENROUTER_COMPLETIONS_PATH` override.
- Spring Boot 4 uses Jackson 3 under the `tools.jackson.*` package for its
  auto-configured mapper. Services that inject the Boot-managed mapper should
  use `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind.ObjectMapper`.
- With Spring 7, components that have more than one constructor should mark the
  production constructor with `@Autowired`; otherwise Spring may try a default
  constructor and fail at runtime.
- If adding a package-private constructor for tests, keep the public production
  constructor annotated.

## Production Change Checklist

Before considering production work done:

- [ ] Relevant source/config changes are committed to the repo, not only applied on the VPS.
- [ ] Caddy, Docker Compose, env templates, and workflows are updated when their
      production behavior changes.
- [ ] Backend tests pass after backend changes: `cd Backend && ./gradlew test`.
- [ ] Frontend tests/build pass after frontend changes: `cd Frontend/web && bun run test`, `bun run lint`, `bun run build`.
- [ ] GitHub Actions for the pushed commit are green.
- [ ] Live smoke checks in this runbook pass.
- [ ] Any new owner/manual setup steps are added to `AGENTS.md`.
