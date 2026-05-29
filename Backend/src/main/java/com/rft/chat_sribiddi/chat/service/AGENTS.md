# chat/service/ — Core Chat Orchestration & Spring AI Integration

## OVERVIEW
Largest package (28 files). Contains the chat orchestrator (`ChatService`), SSE streaming (`ChatStreamSubscriber`), Spring AI provider factory, prompt building pipeline, RAG/document context, and media validation.

## WHERE TO LOOK
| Task | File | Notes |
|------|------|-------|
| Chat CRUD + streaming entry | `ChatService.java` (675 lines) | 17 deps, 60+ methods, inner records |
| SSE lifecycle (auth) | `ChatStreamSubscriber.subscribe()` | Persists messages, quota commit/rollback |
| SSE lifecycle (guest) | `ChatStreamSubscriber.subscribeStateless()` | No persistence, parallel code path |
| Provider client creation | `DefaultProviderClientFactory.java` | Caffeine cache, manual ChatClient build |
| Prompt history | `PromptMessageBuilder.java` | Builds Spring AI Message list from DB |
| Memory + RAG advisors | `PromptAdvisors.java` (package-private) | Binds to ChatClient request |
| Tool binding | `PromptToolBinder.java` | Consent, RecordingToolCallback wrapping |
| Chat memory (read-only) | `RepositoryBackedChatMemory.java` | `saveAll()` is no-op |
| Media validation | `MediaValidationService.java` | Size/type checks, base64 handling |
| Document RAG | `DocumentRetrievalService.java` + `DocumentChunker.java` | PDF extraction, chunking |
| Stream concurrency | `StreamConcurrencyGate.java` | Limits parallel streams per user |
| Stream errors | `StreamErrorHandler.java` | Formats SSE error events |
| Provider configs | `OpenAiProviderProperties.java`, `GoogleProviderProperties.java`, `OpenRouterProviderProperties.java` | `@ConfigurationProperties` per provider |
| Chat limits | `ChatProperties.java` | `app.chat.*` — contextMessageLimit, maxMediaAttachments, etc. |

## CONVENTIONS
- **`ChatService`** is the single orchestrator — all chat operations flow through it
- **Inner records** for method grouping: `MessagePreparation`, `PreparedPrompt`, `ResolvedCall`, `OffsetPageRequest`
- **`ProviderClientFactory`** is the interface; `DefaultProviderClientFactory` is `@Primary` impl
- **`ProviderClientHandle`** wraps `ChatClient` + `AutoCloseable` for resource management
- **Package-private** classes (`PromptAdvisors`, `DocumentContextAdvisor`, `RepositoryBackedChatMemory`) are internal to this package

## ANTI-PATTERNS
- **Dual streaming paths** in `ChatStreamSubscriber` — `subscribe()` and `subscribeStateless()` duplicate ~80% of handler logic (refactoring target: extract shared core)
- **`ChatService` at 675 lines** — god service, 17 injected dependencies
- **`RepositoryBackedChatMemory`** parses conversation IDs as `chat-sribiddi:<chatId>:<beforeMessageId>:<maxMessages>` — fragile string protocol
