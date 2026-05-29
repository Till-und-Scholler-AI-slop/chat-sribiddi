# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-29
**Commit:** 9b6fa89
**Branch:** main

## OVERVIEW
AI chat platform (germinai.de) — multi-model (OpenAI, Gemini, Claude, OpenRouter), tiered billing (Free/Premium/Max), web + mobile. Spring Boot 4 / Java 25 backend + Nuxt 4 / Vue 3 / TypeScript web frontend + Flutter mobile prototype. PostgreSQL, Redis, Caddy reverse proxy, Stripe payments.

## STRUCTURE
```
chat-sribiddi/
├── Backend/            # Spring Boot 4 / Java 25 / Spring AI 2.0.0-M8
│   ├── src/main/java/…/chat/       # Core chat: controllers, services, DTOs, entities, streaming
│   ├── src/main/java/…/billing/    # Stripe, quotas, entitlements, plan lifecycle
│   ├── src/main/java/…/auth/       # OAuth2, security, user settings
│   ├── src/main/java/…/keyring/    # Per-user API key encryption/storage
│   ├── src/main/java/…/modelcatalog/ # AI model catalog (live + YAML fallback)
│   ├── src/main/java/…/tools/      # Chat tools (calculator, URL fetcher, Python sandbox)
│   ├── src/main/java/…/websearch/  # Brave Search integration
│   ├── src/main/java/…/config/     # Cross-cutting: rate limiting, caching, exception handling
│   └── src/main/resources/         # application.yaml, Flyway migrations
├── Frontend/
│   ├── web/            # Nuxt 4 / Vue 3 / TypeScript / Nuxt UI v4 / Tailwind v4
│   │   ├── app/composables/  # State stores + API clients (no Pinia)
│   │   ├── app/pages/        # File-based routing (12 pages, flat)
│   │   ├── app/components/   # Lean: 11 components total
│   │   └── i18n/locales/     # de.json (default), en.json
│   └── mobile/         # Flutter/Dart UI prototype (no backend integration yet)
├── infra/caddy/        # Caddy reverse proxy config
├── monitoring/grafana/ # Grafana dashboards
└── docs/               # Operations runbook, architecture decisions
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Chat streaming flow | `Backend/…/chat/service/ChatService.java` → `ChatStreamSubscriber.java` | SSE via SseEmitter, dual auth/stateless paths |
| Add AI provider | `Backend/…/chat/service/DefaultProviderClientFactory.java` | + add to `application.yaml` `ai.models.*` |
| Add chat tool | `Backend/…/tools/` implement `ChatTool` | Register in `ToolAutoRegistration` |
| Billing/plan logic | `Backend/…/billing/EntitlementService.java` | Plan gating, quotas, model price caps |
| Frontend state | `Frontend/web/app/composables/useChatStore.ts` | 993-line god composable (no Pinia) |
| Frontend API calls | `Frontend/web/app/composables/useChatApi.ts` | All HTTP + types in one file |
| i18n strings | `Frontend/web/i18n/locales/{de,en}.json` | `de` is default/source locale |
| DB schema changes | `Backend/src/main/resources/db/migration/V{n}__*.sql` | Flyway only, never ddl-auto |
| Rate limiting | `Backend/…/config/RateLimitFilter.java` | Bucket4j/Redis or in-memory |
| Deployment | `docs/operations.md` | VPS, Caddy, Docker Compose |

## Quick Start

**Frontend:**
```bash
cd Frontend/web
npm run dev          # Development server
npm run build        # Production build
npm run test         # Unit tests (vitest)
npm run test:e2e     # E2E tests (playwright)
npm run lint         # ESLint
```

**Backend:**
```bash
cd Backend
./gradlew bootRun    # Run Spring Boot app (use --args='--spring.profiles.active=dev' locally)
./gradlew test       # Run tests
./gradlew build      # Build JAR
```

## Spring AI Patterns (2.0.0-M8)

**ChatClient API:**
```java
client.prompt()
    .options(options.mutate())
    .messages(promptBatch.requestMessages())
    .advisors(advisorSpec -> advisorSpec
        .param(ChatMemory.CONVERSATION_ID, conversationId)
        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build()))
    .tools(toolSpec -> toolSpec.callbacks(toolCallbacks.toArray(ToolCallback[]::new)))
    .stream()
    .chatResponse()
    .subscribe(chunk -> ..., error -> ..., () -> ...);
```

**Tools:**
- Annotate with `@Tool(description=...)` and `@ToolParam(description=...)`
- Register via `ToolCallbacks.from(toolObject)` → wrap in `RecordingToolCallback`
- Bind to prompt: `promptSpec.tools(toolSpec -> toolSpec.callbacks(...))`

**Advisors:**
- `MessageChatMemoryAdvisor` for conversation history
- `DocumentContextAdvisor` for RAG context injection
- Order matters: memory advisors before RAG advisors

**Streaming:**
- `ChatStreamSubscriber.subscribe()` — stateful (persists messages, logs tool calls)
- `ChatStreamSubscriber.subscribeStateless()` — anonymous chat (no persistence)
- Both use `QuotaReservation` interface (commit/rollback)

**Provider Factory:**
- `DefaultProviderClientFactory.getClient(provider, apiKey, billingMode)`
- Caches `ChatClient` instances per provider with Caffeine (30min TTL)
- All providers get `ObservationRegistry` for metrics/tracing

## i18n

**TL;DR:** Two locales (`de` default, `en`). Never hardcode strings. Use `t('namespace.key')`.

- Files: `Frontend/web/i18n/locales/{de,en}.json`
- Namespaces: `common`, `auth`, `sidebar`, `chat`, `tools`, `models`, `settings`, `errors`, `legal`, `language`
- Special chars: Wrap `@` in quotes: `user{'@'}example.com`
- Errors: Use `useTranslatedError()` composable → maps to `errors.*` namespace

## Owner Setup (Manual Steps)

| Feature | Env Vars | Notes |
|---------|----------|-------|
| **OpenRouter (Free tier)** | `OPENROUTER_API_KEY`, `OPENROUTER_APP_NAME`, `OPENROUTER_REFERRER` | Managed mode for free users. Blank key disables. |
| **Brave Search** | `BRAVE_SEARCH_API_KEY` | Web search tool. Blank = BYOK only. |
| **Stripe Billing** | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_ID_PREMIUM_MONTH`, `STRIPE_PRICE_ID_PREMIUM_YEAR`, `STRIPE_PRICE_ID_MAX_MONTH`, `STRIPE_PRICE_ID_MAX_YEAR` | Blank keys → 503. Webhook endpoint: `POST /billing/webhook` |
| **Legal Pages** | — | Replace `[TODO: Legal text …]` in `de.json`/`en.json` with counsel-reviewed copy |

## Operations

See [`docs/operations.md`](docs/operations.md) for deployment, Caddy routing, OAuth, Docker, and production incidents.
