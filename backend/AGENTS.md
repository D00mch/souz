# Backend

The `:backend` module is a JVM HTTP build for Souz without Compose UI startup, voice capture, speech recognition, hotkeys, or desktop-only agent tools.

It reuses shared runtime components from `:runtime` and exposes a small REST surface:

- `GET /health` returns process and selected-model status.
- `POST /agent` accepts authenticated internal agent requests and returns a single assistant response with message IDs and token usage.

`/agent` exposes the shared runtime tool catalog for backend-safe tools (files, text/web lookup, calculator, analytics, and non-UI config). The backend intentionally omits `WebImageSearch` so startup does not initialize Apache Tika's external parser probes for host binaries such as `ffmpeg`.

`POST /agent` requires `Content-Type: application/json`, `Authorization: Bearer <internal-agent-token>`, and `X-Request-Id: <uuid>`. The token is read from `SOUZ_BACKEND_AGENT_TOKEN` or `souz.backend.agentToken`. The request body `requestId` must match `X-Request-Id`.

The backend keeps `/agent` conversation snapshots in memory by `userId` + `conversationId`. `/agent` executes each turn from a request-scoped runtime:

- Process scope: shared settings/provider clients, shared runtime tool catalog/filter, backend no-op desktop/MCP host adapters, object mappers, and runtime/cache factories.
- Conversation scope: persisted snapshot only, including history, active agent id, temperature, locale, and time zone.
- Request scope: validated request data, model/context/locale/time-zone overrides for the turn, usage tracking reset, and response assembly.

The backend path still reuses the shared `:agent` execution kernel, but it bypasses desktop-only features that are irrelevant here: `AgentFacade`, graph session logging, side-effect/message streams, MCP tool discovery, and desktop/session logging infrastructure.

## Project Structure

```text
backend/
├── build.gradle.kts                            # JVM application build and Ktor server dependencies
├── AGENTS.md                                   # Module notes, routes, and structure
└── src/
    ├── main/
    │   └── kotlin/
    │       └── ru/souz/backend/
    │           ├── app/                        # Entry point, runtime lifecycle, and backend DI wiring
    │           │   ├── BackendMain.kt
    │           │   ├── BackendRuntime.kt
    │           │   └── BackendDiModule.kt
    │           ├── http/                       # Ktor server wrapper, routes, and HTTP guards/errors mapping
    │           │   └── BackendHttpServer.kt
    │           ├── agent/                      # /agent feature internals
    │           │   ├── model/                  # /agent request/response DTOs and validation
    │           │   ├── service/                # Orchestration, dedupe, and concurrency guard
    │           │   ├── runtime/                # Request-scoped runtime factory, adapters, usage tracking
    │           │   └── session/                # Conversation key/snapshot store contract and in-memory impl
    │           └── common/                     # Shared backend exception types
    │               └── BackendRequestException.kt
    └── test/
        └── kotlin/
            └── ru/souz/backend/
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

## Backend Agent Runtime Lifecycle

For `/agent`, `BackendAgentService` is now mostly orchestration:

- validate request
- reject duplicate request ids
- enforce one active request per conversation
- resolve a cached `BackendConversationRuntime`
- execute one turn and assemble the HTTP response

`BackendConversationRuntimeFactory` assembles each request runtime explicitly instead of creating a fresh request-scoped DI container. The runtime loads the persisted in-memory snapshot for the conversation, executes one turn through the shared kernel, and persists the updated snapshot after every successful turn.

Model and `contextSize` remain request-driven. Each turn can change them without losing the conversation history because the runtime reseeds a fresh `AgentContext` from the persisted snapshot and the request’s current model/context window before execution.
