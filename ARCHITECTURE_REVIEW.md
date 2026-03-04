# Architecture Review: chat-sribiddi

## Overview

This document tracks architectural findings across the Spring Boot backend and Nuxt 4 frontend. Items are marked as ✅ Resolved, 🔄 Deferred, or noted with their current status.

---

## Resolved

### 1. ✅ JPA entities exposed directly in REST API

`ChatController` now uses `ChatResponseDTO`, `CreateChatRequestDTO`, and `MessageResponseDTO` records. Entities are no longer exposed in the API.

### 2. ✅ Unbounded provider-client cache (memory leak)

`DefaultProviderClientFactory` now uses Caffeine caches with `expireAfterAccess(30, MINUTES)` and `maximumSize(200)`, with removal listeners that close evicted clients.

### 3. ✅ `SecurityConfig.securityFilterChain` returns null on exception

The method now declares `throws Exception` with no try/catch — Spring handles startup failures gracefully.

### 4. ✅ Frontend tests added

Vitest tests exist for `useChatApi` and `useChatStore` composables in `Frontend/web/app/composables/__tests__/`.

### 5. ✅ All chat message histories fetched on page load

`loadChatsFromBackend` now only fetches messages for the active chat. Other chats are lazy-loaded via `loadMessagesForChat` when selected.

### 6. ✅ CSRF enabled with session-based auth

`SecurityConfig` uses `CookieCsrfTokenRepository.withHttpOnlyFalse()` with a custom `SpaCsrfTokenRequestHandler`. The frontend reads the `XSRF-TOKEN` cookie and sends the `X-XSRF-TOKEN` header on mutating requests.

### 10. ✅ Hardcoded `http://localhost:8080` consolidated

`DEFAULT_BACKEND_BASE_URL` is defined in `useChatApi.ts` and imported by `useAuth.ts`, `useUserSettings.ts`, and `login.vue`. Only `nuxt.config.ts` retains a literal default (build-time config).

### 11. ✅ `useChatApi.deleteChat` uses shared error handling

`deleteChat` now delegates to `requestJson<void>('/chats/${chatId}', { method: 'DELETE' })`.

### 12. ✅ Model catalog configured in YAML

Model lists are defined in `application.yaml` under `ai.models.openai`, `ai.models.google`, and `ai.models.claude`. `ModelCatalogProperties` reads them via `@ConfigurationProperties`.

### 18. ✅ Consistent logging style

All backend service classes now use Lombok `@Slf4j` instead of manual `LoggerFactory.getLogger()`.

### 19. ✅ ChatService limits configurable

`ChatProperties` (`@ConfigurationProperties(prefix = "app.chat")`) externalizes `contextMessageLimit`, `maxMediaAttachments`, `maxMediaAttachmentBytes`, and `maxMediaTotalBytes`. Defaults match the previous hardcoded values and can be overridden via `application.yaml` or environment variables.

---

## Deferred

The following items are acknowledged but deferred due to scope, risk, or dependency considerations.

### 7. 🔄 Duplicated auth validation across controllers

`ChatController.validatePrincipal()` and `ApiKeyController.requireUser()` contain similar null-check logic. Both are small, self-contained, and functional. A shared `HandlerMethodArgumentResolver` could reduce duplication but adds complexity for minimal gain.

### 8. 🔄 `extractAssistantText` fragile deep-object traversal

The recursive fallback approach handles multiple AI provider response shapes. Replacing it requires defining a backend response DTO that normalizes Spring AI's `ChatResponse` — a larger change that should be part of a dedicated backend API contract cleanup.

### 9. 🔄 `MessageEntity.chatId` is a raw UUID

Adding `@ManyToOne` / `@OneToMany` requires a Flyway migration, changes to repository queries, and careful testing with the existing schema. Deferred to avoid migration risk.

### 13. 🔄 No linting or formatting config for frontend

Adding ESLint and Prettier is a setup task that affects the entire frontend codebase and CI pipeline. Should be done as a dedicated configuration PR.

### 14. 🔄 Missing OpenAPI/Swagger documentation

Requires adding the `springdoc-openapi-starter-webmvc-ui` dependency and annotating controllers. Deferred as a separate enhancement.

### 15. 🔄 Unpaginated message history endpoint

Adding pagination to `listMessages` changes the API contract and requires frontend coordination. Deferred until conversation length becomes a concern.

### 16. 🔄 No request rate limiting

Requires a new dependency (e.g., Bucket4j) or custom filter. Should be evaluated as part of a production hardening effort.

### 17. 🔄 Static `CryptoService` reference in `ApiKeyAttributeConverter`

The current pattern with `ApiKeyAttributeConverterBootstrap` works around JPA's limitation of not injecting Spring beans into `@Converter` classes. The approach is functional and well-documented in the code.

### 20. 🔄 No global error boundary on frontend

Adding `app:error` hook or `onErrorCaptured` with a fallback UI is a UX enhancement that should be part of a broader error handling strategy.
