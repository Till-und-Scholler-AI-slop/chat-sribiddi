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
- Backend: TypeScript/Hono container exposed only on `127.0.0.1:8080`
- Backend dependencies: Postgres and Redis from [`Backend/deploy/docker-compose.prod.yml`](../Backend/deploy/docker-compose.prod.yml)
- Frontend deploy config: [`Frontend/web/deploy/docker-compose.prod.yml`](../Frontend/web/deploy/docker-compose.prod.yml)

Caddy routing conventions:

- `/api/*` is routed to the backend with `handle_path`, so the backend receives
  paths without the `/api` prefix.
- `/login/oauth2/*` is routed directly to the backend for the Google login OAuth
  callback. The Hono backend preserves this existing redirect endpoint.
- `/provider-connections/*` is routed directly to the backend for model-provider
  OAuth callbacks such as OpenRouter PKCE and Google provider OAuth.
- `/oauth2/*` is routed directly to the backend as an optional non-`/api`
  authorization entrypoint.
- Everything else is routed to the Nuxt frontend.

Frontend/backend URL conventions:

- Production frontend must use `NUXT_PUBLIC_BACKEND_BASE_URL=/api` or the
  equivalent absolute same-origin value `https://germinai.de/api`.
- Backend routes are registered without an `/api` prefix. The `/api`
  prefix is a Caddy public-path concern.
- OAuth login starts at `/api/oauth2/authorization/google` from the frontend and
  should redirect to Google with `redirect_uri=https://germinai.de/login/oauth2/code/google`.
- Hono builds the Google login `redirect_uri` from `APP_FRONTEND_URL`, not from
  the internal HTTP connection between Caddy and the backend. Production must
  set `APP_FRONTEND_URL=https://germinai.de`.

Public/authenticated application paths:

- `/` redirects to `/chat`.
- `/chat`, legal pages, `/models/*`, and `/api/anonymous/chats/stream` are
  public. Anonymous chat uses only `OPENROUTER/openrouter/free` and stores chat
  history in browser localStorage.
- The backend must have `OPENROUTER_API_KEY` configured only for this guest
  OpenRouter Free path; otherwise anonymous calls return HTTP `503` with
  `MANAGED_PROVIDER_UNCONFIGURED`.
- Signed-in chat uses per-user provider connections. A user must connect the
  selected provider in Settings via API key, OAuth, device flow, local endpoint,
  or the provider-supported method that is enabled in the catalog. Missing
  connections return `API_KEY_REQUIRED`.
- Settings shows these connections in one unified **Our providers / Unsere
  Anbieter** area. `ProviderCatalogService` is the backend source of truth for
  provider names, connection profiles, default endpoint URLs, OAuth/device-code
  capability, and docs links. Do not add a second UI catalog for new providers.
- Google Provider OAuth is separate from Google login OAuth. Configure
  `GOOGLE_PROVIDER_OAUTH_CLIENT_ID` and `GOOGLE_PROVIDER_OAUTH_CLIENT_SECRET`
  when they differ from `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`; set
  `GOOGLE_PROVIDER_OAUTH_USER_PROJECT` to the billed Google Cloud project used
  for `x-goog-user-project`.
- GitHub Copilot provider OAuth uses the official GitHub device-code flow.
  Configure `GITHUB_COPILOT_OAUTH_CLIENT_ID` only after registering a GitHub
  OAuth app with device flow enabled. `GITHUB_COPILOT_OAUTH_SCOPES` defaults to
  `read:user`; add only scopes that the Copilot integration actually needs.
- `/settings`, `/usage`, `/compare`, `/api/chats/import`, account endpoints,
  provider connection endpoints, usage endpoints, and authenticated chat
  endpoints require a valid session.
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
  `GUEST_OPENROUTER_FREE_DAILY_CAP=20` by default. Production should keep Redis
  available because Redis-backed rate limiting and guest quota checks fail
  closed when Redis is configured but unavailable.

Tool configuration:

- Web search uses Brave Search only with the signed-in user's Brave Search
  provider connection from Settings. There is no managed Brave Search key in
  production config.

Provider execution:

- Hono is now the public control plane for auth, tenancy, encrypted provider
  connections, quotas, and SSE.
- Native providers use the Vercel AI SDK provider packages. Catalog providers
  with OpenAI-compatible or local endpoint credentials execute through the AI
  SDK OpenAI-compatible adapter.
- OpenRouter PKCE, Google provider OAuth, GitHub Copilot device code, and the
  optional OpenAI `codex-subscription` and xAI `grok-build-subscription`
  CLI/device flows are the enabled provider OAuth/device flows. The OpenAI
  subscription flow is not a normal OpenAI API OAuth grant; it uses the Codex
  CLI ChatGPT login per user. The xAI subscription flow uses the user's own
  Grok Build session through the `grok` CLI bridge.
- OpenAI `codex-subscription` is a hosted beta behind
  `OPENAI_CODEX_SUBSCRIPTION_ENABLED=false` by default. When enabled, the
  backend must have the `codex` CLI installed and must store every user's
  Codex session under `CODEX_CONNECTIONS_ROOT/{userHash}/codex-subscription`
  with `0700` permissions. Do not expose `codex app-server` publicly, do not
  share one ChatGPT account across users, and do not scrape `chatgpt.com`.
  Model names for this profile come from `codex debug models` at login and are
  refreshed from the per-user `CODEX_HOME`; do not hardcode Codex subscription
  model slugs in `model-catalog.yaml`.
- Revocation/cleanup for OpenAI `codex-subscription` is deleting the provider
  connection in Settings, which removes the isolated `CODEX_HOME` directory.
  Treat any `auth.json` files under `CODEX_CONNECTIONS_ROOT` like passwords and
  keep them out of logs, support bundles, and normal backups unless backups are
  encrypted as secrets.
- xAI has two separate profiles. **API billing** uses the user's own xAI API key
  against `https://api.x.ai/v1`. **Grok Build subscription** is an experimental
  per-user CLI bridge; it is disabled unless `GROK_CLI_ENABLED=true`, the
  official `grok` binary is installed, and `GROK_CLI_SESSIONS_DIR` points at a
  private server-owned directory. The backend must never provide a shared xAI
  subscription, shared Grok login, managed xAI API key, or fallback token for
  this profile. Each user must complete the connection flow with their own xAI
  account, and entitlement validation happens through that user's own Grok
  Build session.
- The Grok Build bridge must run with isolated per-user `HOME`, an isolated temp
  `cwd`, no MCP servers, no terminal capability, no file-write capability,
  deny-by-default permissions, bounded output, bounded request timeouts, and
  one concurrent request per user by default. Do not point the bridge at the app
  repo or mount production secrets into the Grok process environment.
- Composer is not exposed through this bridge until official xAI/Grok Build or
  ACP documentation confirms a stable model selection path. Do not add
  third-party Composer model IDs, raw CLI token reuse, cookie reuse, or
  unofficial Grok web endpoints.
- Qwen portal and MiniMax portal are profile options under their base provider,
  not separate visible providers. They remain documented/experimental until a
  supported implementation exists.
- Existing API-key payloads still work and map to the default `api-key` profile.
  New provider connections are unique by `(user_id, provider_id,
  connection_profile_id)`.

Provider profile defaults:

- Native API-key providers without manual endpoint fields: OpenAI,
  Anthropic Claude, Google Gemini, OpenRouter, and Brave Search.
- OpenAI-compatible gateway defaults:
  OpenRouter `https://openrouter.ai/api/v1`, Qwen API
  `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`, Qwen Coding Plan
  `https://coding-intl.dashscope.aliyuncs.com/v1`, MiniMax
  `https://api.minimax.io/v1`, Z.AI API `https://api.z.ai/api/paas/v4`,
  Z.AI Coding Plan `https://api.z.ai/api/coding/paas/v4`, Mistral
  `https://api.mistral.ai/v1`, DeepSeek `https://api.deepseek.com`, Groq
  `https://api.groq.com/openai/v1`, xAI `https://api.x.ai/v1`, Perplexity
  `https://api.perplexity.ai`, and Together `https://api.together.ai/v1`.
- Local defaults: Ollama `http://localhost:11434/v1`, LM Studio
  `http://localhost:1234/v1`, llama.cpp `http://localhost:8080/v1`, and vLLM
  `http://localhost:8000/v1`.
- Endpoint defaults are prefilled by the backend when the user leaves the field
  blank, but the Settings UI keeps an advanced override for provider-specific
  plans and temporary gateway/proxy corrections.

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

1. `.github/workflows/deploy-backend.yml` validates `Backend` with
   `npm run lint`, `npm run build`, and `npm test`.
2. The workflow uploads the backend source bundle and `infra/caddy`.
3. On the VPS it writes `Backend/.env` from `BACKEND_ENV_FILE`.
4. Optional single GitHub secret `OPENROUTER_API_KEY` overrides the matching
   key in `Backend/.env` when it is set, so rotating the guest OpenRouter Free
   key does not require editing the whole multiline `BACKEND_ENV_FILE` secret.
5. It runs `docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build --remove-orphans`.
   The production compose file uses `Backend/Dockerfile.prod`, runs the Flyway
   service against `src/main/resources/db/migration`, then starts the Node/Hono
   backend image.
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

Also smoke-check the signed-in provider-connection path after backend deploys:
with no ready provider connection, sending a non-guest model request should fail
with `API_KEY_REQUIRED`; after connecting that provider in Settings, the same
provider should stream successfully. The web-search tool should bind only after
a user connects Brave Search.

For the OpenAI `codex-subscription` beta, additionally verify `codex --version`
inside the backend runtime image/host, start the Settings login flow, complete
the ChatGPT/Codex device code with a throwaway test account, send one chat with
one generated model option from `codex debug models`, then delete the connection
and confirm the matching `CODEX_HOME` directory is removed.

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
backend `.env` has `APP_FRONTEND_URL=https://germinai.de` and the OAuth client
configuration includes:

```text
https://germinai.de/login/oauth2/code/google
https://germinai.de/provider-connections/google/callback
https://germinai.de/provider-connections/openrouter/callback
```

The first URL is for app login. The `/provider-connections/google/callback`
URL is for Google as a model provider. OpenRouter PKCE passes its callback URL
at connection-start time, but keep the direct callback route routed through
Caddy because existing provider configurations and browser redirects may still
use it. GitHub Copilot uses device code and does not require an HTTP callback.

## Hono Production Startup Pitfalls

Production startup must be validated through CI/deploy, not only focused unit
tests. Current Hono-specific checks:

- `ENCRYPTION_SECRET` and, if separated, `SESSION_SECRET` must decode to
  32-byte base64 values in production.
- Flyway must finish before the backend starts. If `flyway` exits non-zero,
  check SQL migrations before restarting the backend.
- `POSTGRES_*` values are preferred over hand-written `DATABASE_URL` in Compose
  so special characters in passwords are encoded by the backend config loader.
- `OPENROUTER_BASE_URL` should be an API root such as
  `https://openrouter.ai/api/v1`.
- The old Java sources are still present for audit/fallback, but production
  Dockerfiles and workflows target the TypeScript backend.

## Production Change Checklist

Before considering production work done:

- [ ] Relevant source/config changes are committed to the repo, not only applied on the VPS.
- [ ] Caddy, Docker Compose, env templates, and workflows are updated when their
      production behavior changes.
- [ ] Backend checks pass after backend changes: `cd Backend && npm run lint && npm run build && npm test`.
- [ ] Frontend tests/build pass after frontend changes: `cd Frontend/web && bun run test`, `bun run lint`, `bun run build`.
- [ ] GitHub Actions for the pushed commit are green.
- [ ] Live smoke checks in this runbook pass.
- [ ] Any new owner/manual setup steps are added to `AGENTS.md`.
