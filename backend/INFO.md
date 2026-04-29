# Backend

The `:backend` module is a JVM HTTP build for Souz without Compose UI startup, voice capture, speech recognition, hotkeys, or desktop agent tools.

It reuses shared runtime components from `:runtime` and exposes a small REST surface:

- `GET /health` returns process and selected-model status.
- `POST /chat` accepts a JSON body with only `message` and returns the current conversation history.
- `GET /history` returns the current in-memory conversation history.
- `DELETE /history` clears the in-memory conversation history.
- `POST /agent` accepts authenticated internal agent requests and returns a single assistant response with message IDs and token usage.

`POST /agent` requires `Content-Type: application/json`, `Authorization: Bearer <internal-agent-token>`, and `X-Request-Id: <uuid>`. The token is read from `SOUZ_BACKEND_AGENT_TOKEN` or `souz.backend.agentToken`. The request body `requestId` must match `X-Request-Id`.

The backend keeps one legacy `/chat` conversation per process and keeps `/agent` session state in memory by `userId` + `conversationId`.
`/agent` executes through the shared `:agent` graph/runtime logic with backend no-op implementations for desktop/tools SPI contracts.

## Project Structure

```text
backend/
├── build.gradle.kts                            # JVM application build and Ktor server dependencies
├── INFO.md                                     # Module notes, routes, and structure
└── src/
    ├── main/
    │   └── kotlin/
    │       └── ru/souz/backend/
    │           ├── BackendMain.kt              # CLI entry point, host/port/token config, shutdown hook
    │           ├── BackendRuntime.kt           # Backend DI container and runtime lifecycle
    │           ├── BackendDiModule.kt          # Kodein module for backend settings/providers/services
    │           ├── BackendHttpServer.kt        # Ktor Netty server, routing, JSON, auth, and HTTP error mapping
    │           ├── ChatService.kt              # Legacy /chat direct LLM conversation service
    │           ├── BackendAgentService.kt      # /agent shared-runtime execution and conflict handling
    │           ├── BackendAgentModels.kt       # /agent request/response DTOs and validation
    │           ├── AgentSessionRepository.kt   # /agent session storage contract + in-memory impl
    │           └── BackendAgentHostAdapters.kt # Backend SPI implementations for agent runtime
    └── test/
        └── kotlin/
            └── ru/souz/backend/
                ├── BackendChatServiceTest.kt
                └── BackendAgentServiceTest.kt
```

## Internal Agent Route

`POST /agent` request body:

```json
{
  "requestId": "3addc960-3b7c-4f3b-acf5-eb687c39a7cb",
  "userId": "9d243496-4c5e-4f53-a55e-4fe65092613e",
  "conversationId": "5be87fa3-9b57-4cc8-91ea-02f093851a29",
  "prompt": "Напиши короткое резюме проекта",
  "model": "GigaChat-Max",
  "contextSize": 16000,
  "source": "web",
  "locale": "ru-RU",
  "timeZone": "Europe/Moscow"
}
```

Success response:

```json
{
  "requestId": "3addc960-3b7c-4f3b-acf5-eb687c39a7cb",
  "conversationId": "5be87fa3-9b57-4cc8-91ea-02f093851a29",
  "userMessageId": "834820cb-d9ae-4ce6-aa90-d4676a13d625",
  "assistantMessageId": "2f3694ee-dc58-4aa1-9aa5-9c5c02c9c9a1",
  "content": "Souz — это AI-ассистент...",
  "model": "GigaChat-Max",
  "provider": "GIGA",
  "contextSize": 16000,
  "usage": {
    "promptTokens": 1200,
    "completionTokens": 340,
    "totalTokens": 1540,
    "precachedTokens": 0
  }
}
```

Error mapping:

- `401` missing or invalid internal token.
- `400` invalid JSON, missing required fields, invalid UUIDs, mismatched request id header, invalid `contextSize`, or invalid `timeZone`.
- `404` user or conversation not found when a user/conversation resolver is configured.
- `409` duplicate `requestId` or concurrent active request for the same user conversation.
- `500` LLM/runtime failure.
