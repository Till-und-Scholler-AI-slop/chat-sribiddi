# Architecture Review: chat-sribiddi

## Overview

This document identifies 20 architectural findings across the Spring Boot backend and Nuxt 4 frontend, organized by priority. Each finding includes the affected files, the problem, and the recommended fix.

---

## High Priority

### 1. JPA entities exposed directly in REST API

`ChatController` accepts and returns `ChatEntity` directly. `MessageEntity` is also returned raw from `listMessages`. This couples the API contract to the database schema — any schema change is a breaking API change, and persistence details (e.g. `@PrePersist`) leak to consumers.

- **Files:** `Backend/.../chat/controller/ChatController.java`, `Backend/.../chat/model/ChatEntity.java`, `Backend/.../chat/model/MessageEntity.java`
- **Fix:** Create `ChatResponseDTO`, `CreateChatRequestDTO`, and `MessageResponseDTO` records. Map between entity and DTO in the controller.

### 2. Unbounded provider-client cache (memory leak)

`DefaultProviderClientFactory` caches `ChatClient` instances per API key in `ConcurrentHashMap` with no eviction. If a user rotates their key, the old client stays cached forever.

- **File:** `Backend/.../chat/service/DefaultProviderClientFactory.java`
- **Fix:** Replace `ConcurrentHashMap` with a Caffeine cache using `expireAfterAccess(30, MINUTES)` and `maximumSize(200)`. Add a removal listener that calls `ClientHolder.close()`.

### 3. `SecurityConfig.securityFilterChain` returns null on exception

If the security chain configuration fails, the catch block logs the error and returns `null`, causing a hard-to-debug `NullPointerException` at startup instead of a clear failure.

- **File:** `Backend/.../auth/config/SecurityConfig.java`
- **Fix:** Remove the try/catch or rethrow as an unchecked exception. Spring handles startup failures gracefully.

### 4. Zero frontend tests

There are no `*.spec.ts` or `*.test.ts` files in the frontend. Refactoring is risky without test coverage.

- **Fix:** Set up Vitest with `@nuxt/test-utils`. Add unit tests for composables (`useChatStore`, `useChatApi`) and component tests for key components.

### 5. All chat message histories fetched on page load

`loadChatsFromBackend` in `useChatStore.ts` calls `fetchChatMessages()` for every chat session in `Promise.all`. A user with 50 chats triggers 50+ concurrent requests and loads all message data into memory.

- **File:** `Frontend/web/app/composables/useChatStore.ts`
- **Fix:** Only fetch messages for the active chat. Lazy-load messages for other chats when the user selects them.

### 6. CSRF disabled with session-based auth

Both `SecurityConfig` and `DevSecurityConfig` disable CSRF. Since the app uses session cookies with `credentials: 'include'`, it's vulnerable to CSRF attacks.

- **Files:** `Backend/.../auth/config/SecurityConfig.java`, `Backend/.../auth/config/DevSecurityConfig.java`
- **Fix:** Enable CSRF with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and send the CSRF token from the frontend. Alternatively, switch to stateless JWT auth.

---

## Medium Priority

### 7. Duplicated auth validation across controllers

`ChatController.validatePrincipal()` and `ApiKeyController.requireUser()` both null-check `OidcUser` with identical logic, repeated in every endpoint.

- **Files:** `Backend/.../chat/controller/ChatController.java`, `Backend/.../keyring/ApiKeyController.java`
- **Fix:** Use a `HandlerMethodArgumentResolver` or Spring Security's built-in mechanisms to reject null principals before controller code runs.

### 8. `extractAssistantText` is fragile deep-object traversal

The function in `useChatStore.ts` recursively traverses an unknown response shape with many fallback paths (`.text`, `.content`, `.output`, `.message`, `.result`, `.results`). This suggests the backend response contract is unclear.

- **File:** `Frontend/web/app/composables/useChatStore.ts`
- **Fix:** Define a clear response DTO on the backend with an explicit `assistantText` field instead of leaking Spring AI's internal `ChatResponse` structure.

### 9. `MessageEntity.chatId` is a raw UUID, not a JPA relationship

No `@ManyToOne` relationship means no cascading deletes, no JPA referential integrity, and requires manual `messageRepository.deleteByChatId()` in `ChatService.deleteChat()`.

- **File:** `Backend/.../chat/model/MessageEntity.java`
- **Fix:** Add `@ManyToOne(fetch = FetchType.LAZY)` on `MessageEntity` and `@OneToMany(cascade = CascadeType.REMOVE, orphanRemoval = true)` on `ChatEntity`.

### 10. Hardcoded `http://localhost:8080` in 5+ frontend files

The default backend URL is duplicated in `useChatApi.ts`, `useAuth.ts`, `useUserSettings.ts`, `nuxt.config.ts`, and `login.vue`.

- **Fix:** Define a single `DEFAULT_BACKEND_URL` constant in a shared file and import it everywhere.

### 11. `useChatApi.deleteChat` duplicates error handling

The `deleteChat` method manually re-implements the same fetch + 401-check + error-text logic that `requestJson` already provides.

- **File:** `Frontend/web/app/composables/useChatApi.ts`
- **Fix:** Replace with `requestJson<void>('/chats/${chatId}', { method: 'DELETE' })`.

### 12. Model catalog hardcoded in Java

`ModelCatalogProperties` hardcodes model lists as Java defaults. Adding or removing a model requires a code change and redeploy.

- **File:** `Backend/.../modelcatalog/ModelCatalogProperties.java`
- **Fix:** Move defaults to `application.yml` under `ai.models.*` so they can be updated without code changes.

### 13. No linting or formatting config for frontend

No ESLint, Prettier, or similar configuration exists for the frontend code.

- **Fix:** Add `@nuxt/eslint` with recommended preset, `.prettierrc`, and pre-commit hooks.

---

## Lower Priority

### 14. Missing OpenAPI/Swagger documentation

Swagger UI paths are permitted in `SecurityConfig` but there's no `springdoc` dependency. Add `springdoc-openapi-starter-webmvc-ui` and annotate controllers.

### 15. Unpaginated message history endpoint

`ChatService.listMessages` returns all messages for a chat. Long conversations will become slow.

- **File:** `Backend/.../chat/service/ChatService.java`
- **Fix:** Add `Pageable` parameter and return `Page<MessageEntity>`.

### 16. No request rate limiting

No rate limiting on AI provider endpoints. A single user could spam requests.

- **Fix:** Add rate limiting on `POST /chats/{chatId}/messages` (e.g. Bucket4j or a Spring filter).

### 17. Static `CryptoService` reference in `ApiKeyAttributeConverter`

Uses a static field with manual `initialize()` call — fragile and not idiomatic Spring.

- **File:** `Backend/.../keyring/ApiKeyAttributeConverter.java`
- **Fix:** Use Spring's `@Component` + `@Converter(autoApply = true)` pattern.

### 18. Inconsistent logging style

Mix of `LoggerFactory.getLogger()` and Lombok `@Slf4j` across backend classes.

- **Fix:** Standardize on `@Slf4j` across all classes.

### 19. Non-configurable ChatService limits

`CONTEXT_MESSAGE_LIMIT`, `MAX_MEDIA_ATTACHMENTS`, `MAX_MEDIA_ATTACHMENT_BYTES`, `MAX_MEDIA_TOTAL_BYTES` are hardcoded constants.

- **File:** `Backend/.../chat/service/ChatService.java`
- **Fix:** Move to a `@ConfigurationProperties` class so they can be tuned per environment.

### 20. No global error boundary on frontend

Uncaught component errors will show a blank screen. Errors are only caught per-composable with `try/catch` + `toast.add()`.

- **Fix:** Add Nuxt's `app:error` hook or `onErrorCaptured` with a fallback UI.
