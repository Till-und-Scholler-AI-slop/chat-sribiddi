# Backend

Spring Boot + Spring AI service that stores chats and proxies to OpenAI or Google Gemini models.

## Run it
- Prereqs: Docker, JDK 21+, and environment variables. Copy `.env.example` to `.env` and fill `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_PROJECT_ID` (used as `spring.ai.google.genai.project-id`), `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `OPENAI_API_KEY`.
  - Google GenAI needs `GOOGLE_LOCATION` (e.g., `us-central1`) and credentials. Either:
    - Provide `GOOGLE_API_KEY` (GenAI API key), or
    - Set `GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json` for ADC.
- Start Postgres: `docker-compose up -d` (or let Spring start via `compose.yaml`).
- Run the app: `./gradlew bootRun` or use your IDE.
- Auth: only `/` and `/login` are public; everything else requires OAuth2 login (Google). `/secured` is a simple check endpoint.
- Docs: Swagger UI at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`).
- Local testing without login: start with `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` to disable auth (dev-only).

## API endpoints
- GET `/` — Health check, returns `"Hello World"` (public).
- GET `/secured` — Simple string, requires authentication.
- GET `/models/openai` — Lists configured OpenAI models (`ai.models.openai` in `application.yaml`).
- GET `/models/google` — Lists configured Google models (`ai.models.google`).
- POST `/chats` — Create a chat. Body: `{"title": "string?", "userId": "string?"}`. Response includes generated `chatId` (UUID) and timestamps.
- POST `/chats/{chatId}/messages` — Send a message in a chat.
  - Body: `{"content": "Hello!", "provider": "OPENAI|GOOGLE", "modelId": "gpt-4o-mini"}`.
  - Behavior: appends user message, builds a prompt with the last 20 stored messages plus a system prompt `"You are a helpful assistant."`, then calls the chosen LLM.
  - Response: Spring AI `ChatResponse` from the provider; assistant reply is persisted with role `ASSISTANT`.

## Data model (JPA)
- `ChatEntity` (`chats` table): `chatId` (UUID, PK), `title`, `createdAt` (auto-set), `userId`.
- `MessageEntity` (`message` table): `id` (PK), `chatId` (UUID FK reference), `message` (TEXT), `context` (TEXT, optional note on context used), `role` (`SYSTEM|USER|ASSISTANT`), `createdAt` (auto-set).
  - Messages are stored in reverse-chronological order and trimmed to the latest 20 when building a prompt.
- `User` (`users` table): `id`, `email` (unique), `providerId` (OIDC subject).

## Model catalog defaults
- OpenAI: `gpt-4o-mini`, `gpt-4o`, `o1-mini`, `o1` (configurable via `ai.models.openai`).
- Google: `gemini-1.5-flash`, `gemini-1.5-pro`, `gemini-2.0-flash-lite-preview-02-05` (configurable via `ai.models.google`).
