# chat/service/ — Core Chat Orchestration & Spring AI Integration

## OVERVIEW
Largest package (30+ files). Contains the chat orchestrator (`ChatService`), message persistence (`ChatMessageService`), request resolution (`ChatRequestResolver`), SSE streaming (`ChatStreamSubscriber`), Spring AI provider factory, prompt building pipeline, RAG/document context, and media validation.

## WHERE TO LOOK
| Task | File | Notes |
|------|------|-------|
| Chat CRUD + streaming entry | `ChatService.java` (356 lines) | 10 deps, orchestrates message send/stream |
| Message persistence | `ChatMessageService.java` (196 lines) | CRUD, title management, FK violation handling |
| Request resolution | `ChatRequestResolver.java` (159 lines) | Billing/key resolution, prompt preparation, media validation |
| SSE lifecycle (auth + guest) | `ChatStreamSubscriber.java` (569 lines) | Shared `subscribeShared()` with `StreamCallbacks` |
| Provider client creation | `DefaultProviderClientFactory.java` | Caffeine cache (SHA-256 hashed keys), manual ChatClient build |
| Prompt history | `PromptMessageBuilder.java` | Builds Spring AI Message list from DB |
| Memory + RAG advisors | `PromptAdvisors.java` (package-private) | Binds to ChatClient request |
| Tool binding | `PromptToolBinder.java` | Consent, RecordingToolCallback wrapping |
| Chat memory (read-only) | `RepositoryBackedChatMemory.java` | `saveAll()` is no-op |
| Media validation | `MediaValidationService.java` | Size/type checks, base64 handling |
| Document RAG | `DocumentRetrievalService.java` + `DocumentChunker.java` | PDF extraction, chunking |
| RAG config | `RagProperties.java` | `app.chat.rag.*` — maxChunks, targetTokens, etc. |
| Tool config | `ToolProperties.java` | `app.tools.*` — defaultEnabled, timeouts, etc. |
| Stream concurrency | `StreamConcurrencyGate.java` | Limits parallel streams per user |
| Stream errors | `StreamErrorHandler.java` | Formats SSE error events |
| DB constraint checks | `DbConstraintChecker.java` | Shared FK violation detection |
| Provider configs | `OpenAiProviderProperties.java`, `GoogleProviderProperties.java`, `OpenRouterProviderProperties.java` | `@ConfigurationProperties` per provider |
| Chat limits | `ChatProperties.java` | `app.chat.*` — contextMessageLimit, maxMediaAttachments, systemPrompt, etc. |

## CONVENTIONS
- **`ChatService`** orchestrates send/stream — delegates to `ChatMessageService` and `ChatRequestResolver`
- **Top-level records**: `MessagePreparation`, `PreparedPrompt`, `ResolvedCall`, `OffsetPageRequest`
- **`ProviderClientFactory`** is the interface; `DefaultProviderClientFactory` is `@Primary` impl
- **`ProviderClientHandle`** wraps `ChatClient` + `AutoCloseable` for resource management
- **Package-private** classes (`PromptAdvisors`, `DocumentContextAdvisor`, `RepositoryBackedChatMemory`) are internal to this package
- **`StreamCallbacks`** record parameterizes auth vs stateless stream behavior differences

## ANTI-PATTERNS
- **`RepositoryBackedChatMemory`** parses conversation IDs as `chat-sribiddi:<chatId>:<beforeMessageId>:<maxMessages>` — fragile string protocol
