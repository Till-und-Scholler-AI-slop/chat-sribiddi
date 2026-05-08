# Security Operations Notes

This repository includes application-level mitigations, but a few controls still require operator
or product-owner action before production rollout.

## Manual Actions

1. Rotate `ENCRYPTION_SECRET` before deploying this branch if any non-production default or shared
   secret was ever used. Keep old keys in `ENCRYPTION_DECRYPTION_SECRETS` until all stored API keys
   have been re-encrypted.
2. Deploy the updated `infra/caddy/Caddyfile` and reload Caddy so the new security headers are
   actually served at the edge.
3. Set an application-specific outbound User-Agent via `TOOLS_USER_AGENT` and
   `WEB_SEARCH_USER_AGENT` for production support and abuse-contact clarity.
4. Keep `OPENROUTER_ALLOWED_MODEL_CATALOG_HOSTS` narrow. The default is `openrouter.ai`; only add a
   host when it is a trusted catalog provider.
5. Keep `TOOLS_PYTHON_ENABLED=false` in production unless Docker is available and the configured
   `TOOLS_PYTHON_IMAGE` is pre-pulled and maintained. When enabled, the application runs each Python
   execution in an ephemeral container with no network, a read-only filesystem, dropped
   capabilities, resource limits, and no mounted secrets.
6. Production deployments should keep `RATE_LIMIT_BACKEND=redis` so HTTP limits are enforced through
   Redis-backed Bucket4j buckets. `RATE_LIMIT_BACKEND=memory` is only for local development and
   single-process tests.
7. Review CSP after every frontend dependency change. The current policy preserves Nuxt inline
   compatibility; tightening `script-src`/`style-src` further requires nonce/hash support.

## Implemented In-App Mitigations

- Request IDs are returned in `X-Request-Id` and attached to log MDC as `requestId`.
- Production Swagger/OpenAPI endpoints are denied by Spring Security.
- Anonymous Actuator access is limited to `health` and `info`; health details are hidden.
- CSRF cookies use `SameSite=Lax`.
- The OpenRouter model catalog URL is restricted to HTTPS and an allowlisted host.
- Tool and web-search outbound User-Agents are configurable.
- The Python tool strips ANSI escape sequences and executes through a Docker sandbox instead of a
  host interpreter keyword blocklist.
- Frontend Docker runtime uses the non-root `node` user.
- HTTP rate limiting supports Redis-backed Bucket4j buckets with fail-closed 429 behavior when
  Redis is unavailable.

## Security Backlog Split

Issue #248 split the remaining bundled medium/low audit backlog into independently shippable
follow-up tickets. The original bundled issues remain useful as historical audit references, but
new implementation work should happen in the focused follow-up issues rather than adding unrelated
changes to a bundle.

Splitting rules:

- Critical and high-risk work stays ahead of low/informational polish.
- Each follow-up ticket owns one security concern with its own scope and acceptance criteria.
- Bundled backlog issues can be closed as superseded after their remaining items are linked to
  focused follow-up tickets.

Medium backlog split from #244:

- M-01 Markdown HTML sanitization: #252.
- M-03 BYOK API key transport outside JSON bodies: #253.
- M-04 Remove user identifiers from localStorage: #254.
- M-07 Datasource credential log sanitization: #255.
- M-09 Production guard for `DevSecurityConfig`: #256.

Already completed from #244:

- M-02 CSRF cookies use `SameSite=Lax`.
- M-05 Frontend Docker runtime uses the non-root `node` user.
- M-06 Session timeout defaults to 30 minutes.
- M-08 Tool and web-search outbound User-Agents are configurable.

Low/informational backlog split from #245:

- L-01 SRI or self-hosting policy for third-party frontend assets: #257.
- L-04 `security.txt` endpoint: #258.
- L-05 Safe `Content-Disposition` headers for downloads: #259.
- L-06 OAuth callback brute-force protection: #260.
- I-01 OAuth2 PKCE evaluation for native/mobile clients: #261.
- I-02 HSTS at the edge: #262.
- I-03 Application threat model: #263.

Already completed from #245:

- L-02 Secret rotation is documented as a manual operator action.
- L-03 Request ID tracing is implemented with `X-Request-Id` and MDC.
- L-07 Python tool output strips ANSI escape sequences.
