# Copilot Instructions for chat-sribiddi

## Project Overview

`chat-sribiddi` is a lightweight AI API wrapper that provides a web UI for chatting with multiple AI providers (OpenAI, Google Gemini, Anthropic Claude). It consists of:

- **Backend**: Spring Boot 4 application (Java 25) serving a REST API
- **Frontend**: Nuxt 4 / Vue 3 SPA written in TypeScript
- **Database**: PostgreSQL with Flyway migrations
- **Auth**: Google OAuth2 (production), disabled in dev profile

---

## Repository Layout

```
Backend/          Spring Boot application
  src/main/java/com/rft/chat_sribiddi/
    auth/           OAuth2, security config, User entity
    chat/           Core chat feature (controller, service, entities, DTOs, repos)
    config/         Spring beans (ChatClientConfiguration, GlobalExceptionHandler)
    keyring/        Per-user API key encryption/storage
    modelcatalog/   AI model catalog (reads live models or falls back to YAML)
  src/main/resources/
    application.yaml  All externalised config
    db/migration/     Flyway SQL migrations (V1__, V2__, …)

Frontend/web/
  app/
    composables/    useChatStore, useChatApi, useAuth, useKeyApi, useUserSettings
    components/     ChatSidebar.vue, MarkdownMessage.vue
    pages/          chat.vue, login.vue, home.vue, settings.vue, …
    assets/css/     main.css (Tailwind v4 with custom @theme palette)
```

---

## Build & Test Commands

### Backend

```bash
# Compile
./gradlew :Backend:compileJava

# Run all tests
./gradlew :Backend:test

# Run a specific test class
./gradlew :Backend:test --tests "com.rft.chat_sribiddi.chat.service.ChatServiceTest"

# Start (dev profile – no auth, uses docker-compose for Postgres)
cd Backend && ./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Frontend

```bash
cd Frontend/web

# Install dependencies
npm install          # or: bun install

# Dev server (hot reload)
npm run dev          # or: bun run dev

# Build
npm run build        # or: bun run build

# Run tests (Vitest)
npx vitest run       # or: npm test
```

---

## Backend Conventions

### Language & Framework
- **Java 25** with `java.toolchain.languageVersion = 25`
- **Spring Boot 4** (`org.springframework.boot:4.0.1`)
- **Spring AI 2.0.0-M2** (`spring-ai-bom`)
- **Lombok** — use `@Slf4j` for logging (not manual `LoggerFactory.getLogger()`), `@Data`, `@Builder`, etc.

### Package Structure
Follow the existing feature-package pattern: each top-level feature (`chat`, `keyring`, `modelcatalog`, `auth`) owns its own controller, service, entities, DTOs, and repository.

### Controllers & DTOs
- Expose **DTOs only** in REST responses — never JPA entities directly.
- Use Java `record` types for request/response DTOs.
- Controllers validate with `@Valid` and rely on `GlobalExceptionHandler` for error responses.

### Services
- Inject dependencies via constructor (Lombok `@RequiredArgsConstructor`).
- Use `@ConfigurationProperties` beans for any tuneable limits or config values; never hardcode them in service classes.

### Security
- **Non-dev profile** (`SecurityConfig`): CSRF enabled with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and a custom `SpaCsrfTokenRequestHandler`. All endpoints except `/`, `/login`, and Swagger docs require authentication.
- **Dev profile** (`DevSecurityConfig`): All security disabled. Use `--spring.profiles.active=dev` locally.
- When adding new endpoints, annotate them appropriately and update `SecurityConfig.securityFilterChain` if they should be public.

### AI Provider Clients
- Provider clients are created **lazily per request** via `DefaultProviderClientFactory` (Caffeine cache with 30-minute expiry, max 200 entries).
- Spring AI auto-configurations for all three providers are **excluded** in `application.yaml` to allow keyless startup.
- When adding a new provider, follow the `ProviderClientFactory` pattern and add fallback model entries to `application.yaml` under `ai.models.<provider>`.

### Configuration (application.yaml)
| Prefix | Purpose |
|---|---|
| `app.chat` | Chat limits (`contextMessageLimit`, `maxMediaAttachments`, `maxMediaAttachmentBytes`, `maxMediaTotalBytes`) |
| `ai.models` | Fallback model catalogs per provider (`openai`, `google`, `claude`) |
| `app.cors.allowed-origins` | CORS allowed origins |
| `encryption` | AES key config for user API key storage |

All tuneable values must map to a `@ConfigurationProperties` class — do not hardcode them in services.

### Database
- Schema is managed exclusively by Flyway migrations (`src/main/resources/db/migration/V{n}__description.sql`).
- **Never** use `ddl-auto: create` or `ddl-auto: update`.
- Follow the `V{n}__` naming convention for new migrations.

### Testing
- Use **JUnit 5** with `@ExtendWith(MockitoExtension.class)` for unit tests.
- Test classes live in `src/test/java/…` mirroring the main package structure.
- Use `@SpringBootTest` + `@DataJpaTest` sparingly; prefer plain unit tests with mocks.

---

## Frontend Conventions

### Framework & Language
- **Nuxt 4** with **Vue 3** Composition API
- **TypeScript** throughout — all composables, components, and pages must be typed.
- **@nuxt/ui 4** for UI components; icons from `heroicons` and `lucide` via Iconify.

### State Management
- Use `useState<T>(key, init)` (Nuxt's SSR-safe primitive) for shared reactive state inside composables.
- `useChatStore` is the single source of truth for conversations and messages.
- `useChatApi` is the sole API client — do **not** call `fetch`/`$fetch` directly in components.

### API Calls
- All backend requests go through `useChatApi.requestJson<T>()`.
- `requestJson` automatically attaches the `X-XSRF-TOKEN` header on mutating requests (`POST`, `PUT`, `PATCH`, `DELETE`).
- The backend base URL is resolved via `normalizeBaseUrl()` exported from `useChatApi.ts`. Import `DEFAULT_BACKEND_BASE_URL` from there instead of hardcoding `http://localhost:8080`.

### Styling
- **Tailwind CSS v4** with a custom `@theme` palette in `assets/css/main.css`.
- Gray scale is overridden to a blue-navy palette: `gray-950` = Ink Black, `gray-900` = Prussian Blue, `gray-700` = Dusk Blue, `gray-500` = Dusty Denim.
- Dark body text uses `#e0e1dd` (Alabaster Grey); primary accent uses `--ui-primary` (sky blue).
- Stick to the existing palette — do not introduce new arbitrary colors.

### Testing
- Tests use **Vitest** (`vitest.config.ts`).
- Test files live in `app/composables/__tests__/` and mirror the composable they test.
- Focus on pure utility functions and response-parsing logic; avoid testing internal Nuxt/Vue reactivity.

---

## Key Design Decisions

- **No global ESLint/Prettier config** on the frontend yet (deferred — see `ARCHITECTURE_REVIEW.md` §13).
- **`MessageEntity.chatId` is a raw UUID** (no `@ManyToOne` FK) — do not add JPA relations without a Flyway migration (deferred §9).
- **`extractAssistantText`** uses recursive fallback traversal to handle multiple provider response shapes — treat it as stable until a normalised backend DTO is introduced (deferred §8).
- **Static `CryptoService` reference** in `ApiKeyAttributeConverter` is intentional (JPA limitation workaround via `ApiKeyAttributeConverterBootstrap` — see deferred §17).
