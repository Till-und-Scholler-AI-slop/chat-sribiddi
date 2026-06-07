# Provider Gateway

Internal TypeScript gateway for provider adapters that are better served by the OpenClaw/Vercel AI SDK ecosystem or the Codex CLI than by the Hono control plane directly.

This service is intentionally bound to `127.0.0.1` by default and is not a shared OpenClaw profile gateway. Provider credentials remain owned by the Spring backend and must stay user-scoped. Subscription or CLI-style providers must run through isolated per-user credential directories or remain disabled.

Current state:

- `GET /health` returns a health payload.
- `POST /v1/chat/stream` supports a `mock` provider for contract tests.
- `POST /v1/chat/stream` streams OpenAI-compatible providers through Vercel AI SDK
  when the backend passes a user-owned `baseUrl` plus optional `apiKey`.
- `POST /v1/chat/stream` can stream OpenAI `codex-subscription` requests when
  the backend passes `authMethod=SUBSCRIPTION_CLI` and an isolated per-user
  `codexHome`. This uses `codex exec --json` and must stay on an internal
  network.
- Private or local endpoint URLs are rejected by default. Set
  `PROVIDER_GATEWAY_ALLOW_PRIVATE_BASE_URLS=true` only for trusted local/dev
  deployments where backend-to-local-provider access is intended.

Runtime:

```bash
npm install
npm run test
npm run build
PROVIDER_GATEWAY_HOST=127.0.0.1 PROVIDER_GATEWAY_PORT=8095 npm start
```

Backend integration:

- Enable with `PROVIDER_GATEWAY_ENABLED=true`.
- Point the backend at this service with `PROVIDER_GATEWAY_BASE_URL`.
- Keep the service on an internal network only; browser clients never receive
  provider secrets or call this gateway directly.
