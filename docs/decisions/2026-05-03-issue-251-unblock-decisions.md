# Issue #251 Unblock Decisions

Date: 2026-05-03

This decision record captures the owner answers from GitHub issue #251 so the remaining
implementation tickets can proceed without rediscovering product or architecture context.

## Security And Tool Execution

- Python/code execution must run in an ephemeral Docker container per execution.
- Containers must be removed after completion and run with no network access, limited CPU/RAM, and
  a hard timeout around 10 seconds.
- Local development and production should use the same sandbox model.
- The in-memory Python keyword blocklist can be removed after the Docker sandbox is in place.
- Tool execution is automatic by default.
- Code execution, file access, and external APIs with user auth context require explicit consent per
  tool call.
- Low-risk tools such as web search, date/time, and weather may execute without interruption.
- Prompts must not grant tool consent. Consent must come from the user through the UI.
- The API consent state should be represented as `pendingConsent: { toolName, args, riskLevel }`.

## Rate Limiting

- Production rate limiting should use Bucket4j with Redis-backed shared buckets.
- In-memory rate limiting is acceptable only for local/development use.
- Redis outages must fail closed with HTTP 429 instead of falling back to in-memory limits.
- Trusted proxy headers such as `X-Forwarded-For` must be controlled by a configurable whitelist.
- Product-tier quotas are superseded by the BYOK-only v1 strategy. Keep anonymous guest quotas
  separate from authenticated DDoS-oriented request limits.

## Monetization

- Superseded for v1: the app no longer processes in-app payments.
- Signed-in users bring their own provider keys. Guests can use only the configured OpenRouter Free
  path.
- Any future ads, provider account linking, or paid product packaging requires a new decision record.

## E2E Test Environment

- E2E tests use seeded fixed users instead of a real OAuth flow.
- External LLM/OpenRouter/provider calls are mocked.
- The release-blocking happy path is: create a new chat, send a message, receive a mocked response.
- Backend and frontend run against the same mocked infrastructure, ideally through Docker Compose.
- Seed data should include an anonymous guest path, a signed-in user without provider keys, a
  signed-in user with provider keys, and one admin if admin features are under test.

## Model Comparison

- Comparison supports a dynamic number of parallel models.
- One shared prompt is sent to all selected models.
- Streaming happens in parallel per model.
- Cancel stops all active comparison streams.
- Comparison results are session-only by default.
- Users may explicitly choose one comparison chat to keep in normal chat history.
- The layout is side-by-side columns with horizontal scrolling when many models are selected.
- Token and cost information is shown per model column.

## Refactors

- Refactor order is backend `ChatService` first, then frontend `useChatStore`, then `chat.vue`.
- Refactors may include API, store, and UI behavior changes.
- Behavior changes must be documented in the relevant PR/changelog.

## Backlog Splitting

- Bundled medium and low security backlog issues can be closed as superseded after smaller scoped
  follow-up tickets exist.
- Critical and high security work stays prioritized ahead of low/informational polish.
