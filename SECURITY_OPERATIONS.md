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
5. Keep `TOOLS_PYTHON_ENABLED=false` in production unless the backend is running with a dedicated
   container/jail profile: no network, a read-only filesystem, process limits, and no mounted
   secrets. The application defaults this tool off.
6. The current HTTP rate limiter is in-memory and per application instance. For multi-instance
   production deployments, replace or augment it with Redis-backed shared limits before scaling out.
7. Review CSP after every frontend dependency change. The current policy preserves Nuxt inline
   compatibility; tightening `script-src`/`style-src` further requires nonce/hash support.

## Implemented In-App Mitigations

- Request IDs are returned in `X-Request-Id` and attached to log MDC as `requestId`.
- Production Swagger/OpenAPI endpoints are denied by Spring Security.
- Anonymous Actuator access is limited to `health` and `info`; health details are hidden.
- CSRF cookies use `SameSite=Lax`.
- The OpenRouter model catalog URL is restricted to HTTPS and an allowlisted host.
- Tool and web-search outbound User-Agents are configurable.
- The Python tool strips ANSI escape sequences and no longer falls back to a shell-based `su` path.
- Frontend Docker runtime uses the non-root `node` user.

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
