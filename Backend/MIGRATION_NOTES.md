# Hono Migration Notes

The Hono backend preserves the existing frontend-compatible API surface and DB
schema, but these intentional differences remain:

- OpenRouter PKCE and Google provider OAuth are implemented. Other cataloged
  provider-specific OAuth/device flows still return `UNAVAILABLE`.
- Google login OAuth is implemented with signed Hono cookies rather than Spring
  Security sessions. Endpoint URLs remain compatible.
- Document upload extraction supports text-like files (`text/*`, JSON, XML,
  NDJSON, YAML, Markdown) and PDF. Unsupported document types, including DOCX,
  are rejected instead of being stored.
- Calculator, current date/time, and URL fetcher are model-side AI SDK tools and
  are logged as tool-call records. Brave Search is still applied as prompt
  enrichment and logged as a tool-call record rather than as a model-side tool.
- Provider-gateway/catalog providers execute through OpenAI-compatible endpoint
  credentials where available. Arbitrary provider-specific OAuth/device flows
  beyond OpenRouter PKCE and Google OAuth are not reimplemented yet.
- Cost calculation remains `null` for new usage events; token totals are
  preserved from provider usage metadata when available.
- Java/Gradle backend sources were removed after Hono startup, Flyway, tests,
  Docker build, and smoke checks passed. Docker, CI, and runtime entrypoints now
  target Hono.
