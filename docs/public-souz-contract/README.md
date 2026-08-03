# Client-Souz Contract

Draft public contract for Client integrations with Souz Cloud.

The canonical frame trace is [examples/happy-path.jsonl](examples/happy-path.jsonl). This document records the rules that are not obvious from that trace. [openapi.yaml](openapi.yaml) keeps the REST endpoint and reusable WebSocket frame schemas machine-readable.

Local API-client setup for the HTTP request and WebSocket happy path is in [postman/](postman/) and [insomnia/](insomnia/).

## Boundary

Client owns audio ingestion, external token validation, ASR, TTS, screen rendering, and device actions. Souz receives trusted `userId` values, recognized text, device metadata, and tool results. This API is only exposed inside a trusted environment, so the public contract does not require credentials.

Public client kinds:

- `backend`: server-to-server client.
- `mobile_app`: direct app client.

`clientType` is declared in `POST /v1/chats` and `/v1/chats/{chatId}/ws?clientType=...`. The WebSocket `clientType` must match the chat's stored `clientType`.

## Flow

```text
POST /v1/chats {userId, requestId, clientType} -> chatId
connect /v1/chats/{chatId}/ws?clientType=...
client -> souz: message.submit
souz -> client: ack with threadId, inputSeq, revision
souz -> client: status with current thread liveness
souz -> client: tool.call.started
client -> souz: tool.result
souz -> client: tool result ack
souz -> client: thread.completed | thread.failed | thread.cancelled
```

## HTTP

`POST /v1/chats` creates a durable user-owned chat.

Request:

- `userId`: trusted user identity for chat ownership.
- `requestId`: client-generated idempotency key for chat creation.
- `clientType`: `backend` or `mobile_app`.
- `title`: optional string or null.

Success response:

- `200` for an idempotent retry, `201` for first creation.
- `requestId`, `duplicate`, `chat.id`, `chat.title`.

Create-chat idempotency is scoped by `(userId, requestId)`, where `userId` comes from the JSON body. The normalized payload includes `clientType` and `title`. Same key and payload returns the same chat with `duplicate = true`; same key with different payload returns `409 idempotency_conflict`.

`GET /v1/chats/{chatId}/threads/{threadId}?clientType=...` returns the current durable status for a public thread. Use it as a liveness probe when a socket is disconnected, when no event has arrived within the client's expected window, or when an idempotent retry returns a stored acknowledgement. The response includes `status`, `alive`, `acceptsInput`, `revision`, timestamps, runtime lease expiry, and terminal `error` when present.

## WebSocket

Route: `/v1/chats/{chatId}/ws?clientType=...&afterSeq=...`

`afterSeq` is optional and exclusive. If omitted, Souz treats it as `0` and replays every durable event in the chat. On connect, Souz sends events with `seq > afterSeq` in order, then live frames. `seq` is chat-local, monotonic, and used for replay and deduplication. A separate `eventId` is not used.

Client frames:

- `message.submit`: `{kind, chatId, requestId, threadId?, payload}`. `payload.device.userId` carries the trusted user scope. Omit `threadId` for the first submission in a thread.
- `tool.result`: `{kind, chatId, threadId, toolCallId, status, result|error}`. `status` is `succeeded`, `failed`, `cancelled`, or `timed_out`.
- `thread.cancel`: `{kind, chatId, requestId, threadId, reason?}`.

Souz frames:

- `ack`: acknowledgement for accepted or rejected client frames.
- `status` with `type = thread.status`: live-only current thread status sent after accepted `message.submit` and `thread.cancel` acknowledgements. This frame is not durable and is not replayed.
- `event` with `type = tool.call.started`: includes `threadId`, `toolCallId`, `target`, `name`, `arguments`, optional `deviceId`, and optional `deadlineAt`.
- terminal `event` with `type = thread.completed | thread.failed | thread.cancelled`.

Tool `target` is only `souz` or `client`. The connected Client side can be `backend` or `mobile_app`, but that does not create a third tool target.

## Frame Reference

The canonical schema names are in `openapi.yaml` components. All frames have `additionalProperties = false`.

Client-to-Souz frames:

- `MessageSubmit`: `{kind: "message.submit", chatId, requestId, threadId?, payload}`.
- `SucceededToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "succeeded", result}`.
- `FailedToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "failed", error}`.
- `CancelledToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "cancelled", error}`.
- `TimedOutToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "timed_out", error}`.
- `ThreadCancel`: `{kind: "thread.cancel", chatId, requestId, threadId, reason?}`.

Souz-to-Client acknowledgements:

- `AcceptedMessageSubmitAck`: `{kind: "ack", chatId, requestId, status: "accepted", duplicate, submission, thread, error: null, receivedAt}`.
- `RejectedMessageSubmitAck`: `{kind: "ack", chatId, requestId, status: "rejected", duplicate, thread: null, error, receivedAt}`.
- `AcceptedToolResultAck`: `{kind: "ack", chatId, toolCallId, threadId, status: "accepted", duplicate, error: null, receivedAt}`.
- `RejectedToolResultAck`: `{kind: "ack", chatId, toolCallId, threadId, status: "rejected", duplicate, error, receivedAt}`.
- `AcceptedThreadCancelAck`: `{kind: "ack", chatId, requestId, threadId, status: "accepted", duplicate, error: null, receivedAt}`.
- `RejectedThreadCancelAck`: `{kind: "ack", chatId, requestId, threadId, status: "rejected", duplicate, error, receivedAt}`.

Souz-to-Client live status:

- `ThreadStatusFrame`: `{kind: "status", type: "thread.status", chatId, threadId, requestId, status, alive, acceptsInput, revision, startedAt, finishedAt, runtimeLeaseExpiresAt, error, observedAt}`.

Souz-to-Client events:

- `ToolCallStartedEvent`: `{kind: "event", seq, type: "tool.call.started", chatId, threadId, payload, createdAt}`.
- `ThreadCompletedEvent`: `{kind: "event", seq, type: "thread.completed", chatId, threadId, payload: {response}, createdAt}`.
- `ThreadFailedEvent`: `{kind: "event", seq, type: "thread.failed", chatId, threadId, payload: {error}, createdAt}`.
- `ThreadCancelledEvent`: `{kind: "event", seq, type: "thread.cancelled", chatId, threadId, payload: {reason?}, createdAt}`.

## Threads

A thread is a task inside a chat. A chat can outlive many WebSocket connections and many threads.

The first accepted `message.submit` creates a thread. The acknowledgement returns:

- `submission.inputSeq`: thread-local sequence of accepted user input.
- `thread.id`.
- `thread.created`: `true` for first submission, `false` for continuation.
- `thread.status = running`.
- `thread.revision`: latest accepted input sequence.

Additional accepted submissions to a running thread append to its input log. The agent must observe every committed input before terminal state. A public `thread.started` event is not emitted because the first ack carries that state; live `thread.status` frames provide immediate non-replayable feedback.

Each thread has exactly one terminal event. If completion and cancellation race, first persisted terminal state wins. If `message.submit` commits before terminal, terminal output must account for it; if terminal commits first, the submission is rejected with `thread_already_terminal`.

## Idempotency

`message.submit` and `thread.cancel` use `(chatId, requestId)`. Same key and normalized payload returns the original ack with `duplicate = true`; same key with a different payload returns rejected ack with `idempotency_conflict`.

`tool.result` uses `(chatId, threadId, toolCallId)`. Repeating the same terminal result returns accepted ack with `duplicate = true` and does not append another event. Reusing the same key with a different terminal payload returns rejected ack with `duplicate = false` and `idempotency_conflict`.

Tool-result acknowledgements are outside the event sequence.

## Backend Mapping

- `chats` stores `clientType`, create `requestId`, and its normalized payload hash.
- `agent_executions` is the thread store; the execution ID is `threadId`, with revision and latest device context.
- `messages.metadata` stores accepted input sequence, source, device, request ID, and request metadata.
- `client_requests` stores the shared message/cancel idempotency scope and original acknowledgement.
- `tool_calls` stores complete client call arguments, deadline, result or error, and tool-result idempotency state.
- `agent_events` stores replayable client tool-start and terminal events with chat-local sequence values.

Client operations are tool-backed Skills supplied through the same `SkillRegistryRepository` contract used by desktop Skills. Bundled definitions live under `backend/src/main/resources/skills/client`; each `SKILL.md` declares its operation ID, category, timeout, and instructions. All client Skills share one WebSocket transport, and each live invocation suspends until `tool.result` or its deadline. The WebSocket feature requires `SOUZ_BACKEND_AGENT=skills`.
