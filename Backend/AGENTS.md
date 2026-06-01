# Backend — Spring Boot 4 / Java 25 / Spring AI 2.0.0-M8

## OVERVIEW
REST API for multi-model AI chat with tiered billing. Feature-package architecture — each domain owns controllers, services, entities, repos, DTOs.

## STRUCTURE
```
src/main/java/com/rft/chat_sribiddi/
├── chat/           # Core chat (largest package, layered sub-packages)
│   ├── controller/ # ChatController, AnonymousChatController, ChatToolController
│   ├── service/    # 28 files: ChatService orchestrator, streaming, Spring AI integration
│   ├── dto/        # Request/response records (RequestDTO, ChatResponseDTO, etc.)
│   ├── model/      # JPA entities (ChatEntity, MessageEntity, etc.)
│   ├── repository/ # Spring Data JPA repos
│   ├── exception/  # ChatNotFoundException
│   └── toolcall/   # RecordingToolCallback, ToolExecutionCollector
├── billing/        # Stripe, entitlements, quotas (23 files, FLAT — no sub-packages)
├── auth/           # OAuth2, security, user settings (flat except config/)
├── keyring/        # Per-user API key encryption (flat, 7 files)
├── modelcatalog/   # AI model catalog (flat, 6 files)
├── tools/          # Chat tools: calculator, URL fetcher, Python sandbox
│   └── api/        # ChatTool interface, ToolRegistry, ToolResult
├── websearch/      # Brave Search (flat, 3 files)
├── audit/          # Audit log (flat, 3 files)
├── observability/  # ChatMetrics (Micrometer counters/timers)
└── config/         # Cross-cutting: GlobalExceptionHandler, rate limiting, caching
```

## CONVENTIONS
- **Java 25**, Spring Boot 4.0.6, Gradle Kotlin DSL (9.5.1)
- **Feature-package layout**: `chat/` uses layered sub-dirs; `billing/`, `auth/`, `keyring/` are FLAT (inconsistency is known)
- **DTOs are `record` types** with static `from()` factories — never expose JPA entities in REST
- **Lombok**: `@RequiredArgsConstructor` for DI, `@Slf4j` for logging, `@Getter @Setter` on entities (NOT `@Data`)
- **`@ConfigurationProperties`** for all tuneable values — never hardcode limits in services
- **Flyway only** for schema — `ddl-auto: none`, migrations in `db/migration/V{n}__*.sql`
- **Tests**: JUnit 5 + Mockito + AssertJ. `@ExtendWith(MockitoExtension.class)`, no `@SpringBootTest` (disabled in CI). Manual service construction in tests.

## SPRING AI PATTERNS
- **All auto-configs excluded** — provider clients built manually via `DefaultProviderClientFactory`
- **Caffeine cache** (30min TTL, max 200) for `ChatClient` instances per provider+key+billingMode
- **Prompt pipeline**: `PromptMessageBuilder` → `PromptAdvisors` (memory+RAG) → `PromptToolBinder` (tools with `RecordingToolCallback`)
- **`RepositoryBackedChatMemory.saveAll()` is a no-op** — app persists messages itself
- **SSE streaming**: `ChatStreamSubscriber` has dual paths (auth + stateless), `SseEmitter` lifecycle

## ANTI-PATTERNS (THIS PROJECT)
- **`BillingController`** uses `@Autowired` + `LoggerFactory` (every other class uses Lombok)
- **`MessageEntity.chatId`** is raw UUID, no `@ManyToOne` — do NOT add JPA relations without Flyway migration
- **Static `CryptoService`** in `ApiKeyAttributeConverter` — intentional JPA limitation workaround
- **`ChatRuntimeOptions`** has 4 overloaded compact constructors (backward compat smell)
- **Inner records in controllers** — `ChatController.UpdateChatPayload`, `BillingController.CheckoutSessionRequest` etc.
- **`Dotenv`** loads `.env` before Spring startup — non-standard but intentional
- **k6 load tests** in `src/test/k6/` — JavaScript inside Java source tree (should be at project root)

## KEY DESIGN DECISIONS
- No JPA relationships (FK integrity via DB constraints, not `@ManyToOne`)
- `spring.ai.chat.client.enabled: false` — custom ChatClient factory instead
- Spring AI auto-configs for OpenAI/Google/Anthropic all excluded
- Dual security profiles: `dev` (all open) vs `!dev` (OAuth2 + CSRF)
- `RequestDTO.apiKey` field is `@Deprecated @JsonIgnore` — request-supplied keys silently ignored

## COMMANDS
```bash
./gradlew bootRun --args='--spring.profiles.active=dev'  # Local dev (no auth)
./gradlew test                                            # All tests
./gradlew test --tests "…ChatServiceTest"                 # Single test class
docker compose up -d                                      # Postgres + Redis only
```
