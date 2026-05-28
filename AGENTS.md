# Agent Guidelines

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
./gradlew bootRun    # Run Spring Boot app
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
