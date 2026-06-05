# app/composables/ — State Stores + API Clients

## OVERVIEW
17 files implementing all frontend state management and API communication. No Pinia — uses raw `useState()` with composables calling each other, creating implicit coupling. Two non-composable utility files (`chat-helpers.ts`, `chat-stream.ts`) break the `use*` naming convention.

## WHERE TO LOOK
| File | Lines | Role |
|------|-------|------|
| `useChatStore.ts` | 993 | **God composable** — chat CRUD, streaming, guest sync, message editing, 6 computed refs |
| `useChatApi.ts` | 684 | **API client + type definitions** — 16 type exports + 16 API methods + generic HTTP layer |
| `useCompareStore.ts` | 458 | Model comparison state — panels, sync, runComparison |
| `useAuth.ts` | ~150 | Auth state — module-level dedup promise for `/secured` check |
| `useUserSettings.ts` | ~120 | User preferences — custom instructions, favorites, auto-title |
| `useModelStore.ts` | ~100 | Model catalog — list, favorites, provider filtering |
| `useKeyApi.ts` | ~60 | API key management — PUT/DELETE/GET per provider |
| `useInstructionsApi.ts` | ~50 | Custom instructions CRUD |
| `useToolCallStore.ts` | ~80 | Tool call logs per message |
| `useFileAttachments.ts` | ~100 | File upload, preview, removal, size validation |
| `useMessageEditing.ts` | ~80 | Inline message edit state + submit |
| `useChatScroll.ts` | ~60 | Auto-scroll to bottom on new messages |
| `useIsMobile.ts` | ~40 | Responsive breakpoint — module-level shared singleton ref |
| `useTranslatedError.ts` | ~30 | Maps error codes to `errors.*` i18n keys |
| `chat-helpers.ts` | 323 | **NOT a composable** — pure functions, type exports, `extractAssistantText` |
| `chat-stream.ts` | ~100 | **NOT a composable** — SSE parsing utility (`parseSseStream`) |
| `__tests__/` | 9 files | Vitest specs co-located with source |

## CONVENTIONS
- **`use*` prefix** for composables that use Vue reactivity (`useState`, `computed`, `ref`)
- **Non-`use*` files** (`chat-helpers.ts`, `chat-stream.ts`) are pure utility modules — auto-imported by Nuxt despite not being composables
- **State pattern**: `useState<T>(key, init)` for SSR-safe shared state; `computed()` for derived values
- **API pattern**: All HTTP via `useChatApi.requestJson<T>()` — never raw `fetch` in composables
- **Test pattern**: `app/composables/__tests__/<name>.test.ts` — manual `vi.stubGlobal()` for Nuxt globals

## ANTI-PATTERNS (THIS DIRECTORY)
- **`useChatStore.ts` (993 lines)** — monolithic god composable, split candidate: state, guest, streaming, messages
- **`useChatApi.ts` (684 lines)** — conflates 16 type exports with API client implementation
- **Module-level mutable state**:
  - `useAuth.ts`: shared `authCheckPromise` — null reset in `finally` can race with concurrent calls
  - `useIsMobile.ts`: shared `sharedIsMobile` + `listenerAttached` — SSR hydration mismatch risk
- **`chat-helpers.ts` and `chat-stream.ts`** — utility files in composables dir, defeat Nuxt auto-import convention; move to `app/utils/` or rename to `use*`
- **Implicit coupling** — `useChatStore` internally calls `useAuth`, `useChatApi`, `useModelStore`, `useToolCallStore`, `useUserSettings`; import one and you transitively import half the app
- **No Pinia** — `useState()` sprawl doesn't scale for this level of cross-composable state

## DEPENDENCY GRAPH (simplified)
```
useChatStore ──→ useAuth, useChatApi, useModelStore, useToolCallStore, useUserSettings
useCompareStore ──→ useChatApi, useModelStore
useModelStore ──→ useChatApi
useAuth ──→ useChatApi
useChatApi ──→ (leaf — generic HTTP + types)
```

## KEY TYPES (exported from useChatApi.ts)
`ModelOption`, `MediaAttachment`, `DocumentAttachment`, `ChatDocument`, `ToolConsent`, `ChatSession`, `BackendMessage`, `ToolCallLog`, `TokenUsage`, `SendMessageResponse`, `RunbookExport`, `ImportChatMessage`, `ImportChat`
