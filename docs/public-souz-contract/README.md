# Client-Souz Contract

Draft public contract for Client integrations with Souz Cloud.

The canonical frame trace is [examples/happy-path.jsonl](examples/happy-path.jsonl). This document records the rules that are not obvious from that trace. [openapi.yaml](openapi.yaml) keeps the only REST endpoint machine-readable.

## Boundary

Client owns audio ingestion, external token validation, ASR, TTS, screen rendering, and device actions. Souz receives authenticated identity, recognized text, device metadata, and tool results.

Public client profiles:

- `backend`: server-to-server profile using `X-Souz-Proxy-Auth` and `X-User-Id`.
- `mobile_app`: direct app profile using `Authorization: Bearer ...`; Souz resolves the authoritative user from the token.

`clientType` is declared in `POST /v1/chats` and `/v1/chats/{chatId}/ws?clientType=...`. The WebSocket `clientType` must match the chat's stored `clientType` and the presented auth profile.

## Flow

```text
POST /v1/chats {requestId, clientType} -> chatId
connect /v1/chats/{chatId}/ws?clientType=...
client -> souz: message.submit
souz -> client: ack with threadId, inputSeq, revision
souz -> client: tool.call.started
client -> souz: tool.result
souz -> client: tool result ack
souz -> client: thread.completed | thread.failed | thread.cancelled
```

## HTTP

`POST /v1/chats` creates a durable user-owned chat.

Request:

- `requestId`: client-generated idempotency key for chat creation.
- `clientType`: `backend` or `mobile_app`.
- `title`: optional string or null.

Success response:

- `200` for an idempotent retry, `201` for first creation.
- `requestId`, `duplicate`, `chat.id`, `chat.title`.

Create-chat idempotency is scoped by `(userId, requestId)`. The normalized payload includes `clientType` and `title`. Same key and payload returns the same chat with `duplicate = true`; same key with different payload returns `409 idempotency_conflict`.

## WebSocket

Route: `/v1/chats/{chatId}/ws?clientType=...&afterSeq=...`

`afterSeq` is optional and exclusive. On connect, Souz sends events with `seq > afterSeq` in order, then live frames. `seq` is chat-local, monotonic, and used for replay and deduplication. A separate `eventId` is not used.

Client frames:

- `message.submit`: `{kind, chatId, requestId, threadId?, payload}`. Omit `threadId` for the first submission in a thread.
- `tool.result`: `{kind, chatId, threadId, toolCallId, status, result|error}`. `status` is `succeeded`, `failed`, `cancelled`, or `timed_out`.
- `thread.cancel`: `{kind, chatId, requestId, threadId, reason?}`.

Souz frames:

- `ack`: acknowledgement for accepted or rejected client frames.
- `event` with `type = tool.call.started`: includes `threadId`, `toolCallId`, `target`, `name`, `arguments`, optional `deviceId`, and optional `deadlineAt`.
- terminal `event` with `type = thread.completed | thread.failed | thread.cancelled`.

Tool `target` is only `souz` or `client`. The connected Client side can be `backend` or `mobile_app`, but that does not create a third tool target.

## Threads

A thread is a task inside a chat. A chat can outlive many WebSocket connections and many threads.

The first accepted `message.submit` creates a thread. The acknowledgement returns:

- `submission.inputSeq`: thread-local sequence of accepted user input.
- `thread.id`.
- `thread.created`: `true` for first submission, `false` for continuation.
- `thread.status = running`.
- `thread.revision`: latest accepted input sequence.

Additional accepted submissions to a running thread append to its input log. The agent must observe every committed input before terminal state. A public `thread.started` event is not emitted because the first ack carries that state.

Each thread has exactly one terminal event. If completion and cancellation race, first persisted terminal state wins. If `message.submit` commits before terminal, terminal output must account for it; if terminal commits first, the submission is rejected with `thread_already_terminal`.

## Idempotency

`message.submit` and `thread.cancel` use `(chatId, requestId)`. Same key and normalized payload returns the original ack with `duplicate = true`; same key with a different payload returns rejected ack with `idempotency_conflict`.

`tool.result` uses `(chatId, threadId, toolCallId)`. Repeating the same terminal result returns accepted ack with `duplicate = true` and does not append another event. Reusing the same key with a different terminal payload returns rejected ack with `duplicate = false` and `idempotency_conflict`.

Tool-result acknowledgements are outside the event sequence.

## Backend Fit

Souz already has trusted identity, user-scoped chats, PostgreSQL messages, agent execution lifecycle, cancellation, durable event replay, and live WebSocket events.

Implementation gaps:

- mobile auth profile at the identity boundary;
- inbound WebSocket handling for `message.submit`, `tool.result`, and `thread.cancel`;
- public thread aggregate over internal executions;
- persisted normalized payload hashes and acknowledgements for the idempotency keys above;
- client-targeted tool adapter that can suspend until `tool.result`;
- storage for `clientType`, input `source`, device capabilities, `inputSeq`, `revision`, and chat-local event `seq`.
