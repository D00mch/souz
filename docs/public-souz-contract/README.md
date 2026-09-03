# Client-Souz Contract

Draft public contract for Client integrations with Souz Cloud.

The canonical frame trace is [examples/happy-path.jsonl](examples/happy-path.jsonl). This document records the rules that are not obvious from that trace. [openapi.yaml](openapi.yaml) keeps the REST endpoint and reusable WebSocket frame schemas machine-readable.

Local API-client setup for the HTTP request and WebSocket happy path is in [postman/](postman/) and [bruno/](bruno/).

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
client -> souz: history.append | message.submit
souz -> client: ack
souz -> client: status with current thread liveness (execute only)
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

`message.submit`, tool results, and cancellations for an active thread are owner-sticky. In multi-replica deployments, those frames must reach the Souz runtime owner that holds the live registry state; the current single-owner contract rejects them as `message_rejected` when they reach a process that only sees the durable running row. `history.append` remains chat-scoped and can be stored on any process. Durable replay and thread status remain available from any process.

Client frames:

- `message.submit`: `{kind, chatId, requestId, threadId?, payload}`. Submits user input for execution and retains explicit/implicit thread selection. Its role is inherently `user` and is not a field.
- `history.append`: `{kind, chatId, requestId, payload}`. Requires `payload.role` (`user` or `assistant`), appends durable chat context, and never selects a thread or triggers model execution.
- `tool.result`: `{kind, chatId, threadId, toolCallId, status, result|error}`. `status` is `succeeded`, `failed`, `cancelled`, or `timed_out`.
- `thread.cancel`: `{kind, chatId, requestId, threadId, reason?}`.

Souz frames:

- `ack`: acknowledgement for accepted or rejected client frames.
- `status` with `type = thread.status`: live-only current thread status sent after accepted `message.submit` and `thread.cancel` acknowledgements. This frame is not durable and is not replayed.
- `event` with `type = tool.call.started`: includes `threadId`, `toolCallId`, `target`, `name`, `arguments`, optional `deviceId`, and optional `deadlineAt`.
- terminal `event` with `type = thread.completed | thread.failed | thread.cancelled`.
- `event` with `type = message.created` and `threadId = null`: an out-of-band message pushed into this chat's history that did not originate from a thread this client started (e.g. forwarded here from another of the user's channels). Ordinary in-thread messages are not delivered on this stream.

Tool `target` is only `souz` or `client`. The connected Client side can be `backend` or `mobile_app`, but that does not create a third tool target.

## Frame Reference

The canonical schema names are in `openapi.yaml` components. All frames have `additionalProperties = false`.

Client-to-Souz frames:

- `MessageSubmit`: `{kind: "message.submit", chatId, requestId, threadId?, payload}`.
- `HistoryAppend`: `{kind: "history.append", chatId, requestId, payload: {role, device, content, meta?}}`.
- `SucceededToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "succeeded", result}`.
- `FailedToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "failed", error}`.
- `CancelledToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "cancelled", error}`.
- `TimedOutToolResult`: `{kind: "tool.result", chatId, threadId, toolCallId, status: "timed_out", error}`.
- `ThreadCancel`: `{kind: "thread.cancel", chatId, requestId, threadId, reason?}`.

Souz-to-Client acknowledgements:

- `message.submit` acknowledgements contain submission and thread details when accepted.
- `history.append` acknowledgements contain neither submission nor thread details and are not followed by thread status.
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
- `MessageCreatedEvent`: `{kind: "event", seq, type: "message.created", chatId, threadId: null, payload: {messageId, seq, role, content, clientMessageId?}, createdAt}`.

## Threads

A thread is a task inside a chat. A chat can outlive many WebSocket connections and many threads.

An explicit `message.submit.threadId` continues that thread. When `threadId` is absent, Souz continues the chat's active thread; it creates a thread only when the chat has no active thread. An active thread that does not accept input rejects the submission without creating a replacement.

The acknowledgement returns:

- `submission.inputSeq`: thread-local sequence of accepted user input.
- `thread.id`.
- `thread.created`: `true` when the originally acknowledged request created the thread, `false` for a continuation.
- `thread.status = running`.
- `thread.revision`: latest accepted input sequence.

Additional accepted submissions to a running thread append to its input log. The agent must observe every committed input before terminal state. A public `thread.started` event is not emitted because an ack with `thread.created = true` carries that state; live `thread.status` frames provide immediate non-replayable feedback.

Each thread has exactly one terminal event. If completion and cancellation race, first persisted terminal state wins. If `message.submit` commits before terminal, terminal output must account for it; if terminal commits first, the submission is rejected with `thread_already_terminal`.

## History

History belongs to the chat and has no thread identity. It is stored with its original user or assistant role without changing an execution, input sequence, revision, cancellation state, runtime lease, or active device context. Its acknowledgement has no execution fields; no thread status or durable event follows it.

An accepted history message notifies a matching active runtime on the same Souz process that durable history is pending. The notification never starts the graph, cancels an LLM request, or interrupts a tool. If the active run naturally reaches another LLM boundary, the pending history is inserted before that request. History received during tool execution is placed before the complete tool call/result exchange so the pair remains valid. If the current LLM response finishes the run, history remains pending until the next execute submission.

The internal chat-local message sequence defines execute barriers. History ordered before an execute input is supplied to that execute exactly once; history ordered after it is eligible for a later safe boundary or the following execute. Storage does not require local runtime ownership. A process without the runtime relies on durable catch-up at the next execute.

## Idempotency

`message.submit`, `history.append`, and `thread.cancel` use `(chatId, requestId)`. Same key, kind, and normalized payload returns the original result with `duplicate = true`; reusing the key with a different kind or payload returns rejected ack with `idempotency_conflict`. Message normalization includes client-supplied nullable `threadId`, content, device, and request metadata. History normalization includes role, content, device, and request metadata. Receipt replay precedes `message.submit` thread selection, while `history.append` bypasses thread selection entirely.

`tool.result` uses `(chatId, threadId, toolCallId)`. Repeating the same terminal result returns accepted ack with `duplicate = true` and does not append another event. Reusing the same key with a different terminal payload returns rejected ack with `duplicate = false` and `idempotency_conflict`.

Tool-result acknowledgements are outside the event sequence.

## Backend Mapping

- `chats` stores `clientType`, create `requestId`, and its normalized payload hash.
- `agent_executions` is the thread store; the execution ID is `threadId`, with revision and latest device context.
- `messages.metadata` stores source, device, request ID, and request metadata. Execute messages also store `inputSeq`; history messages store `clientHistory = true` instead.
- `client_requests` stores the shared message/history/cancel idempotency scope and original acknowledgement.
- `tool_calls` stores complete client call arguments, deadline, result or error, and tool-result idempotency state.
- `agent_events` stores replayable client tool-start, terminal, and out-of-band `message.created` (cross-channel push, `threadId = null`) events with chat-local sequence values.

Client operations are backend-owned tool-backed Skills defined by indexed classpath `SKILL.md` resources. Their argument shapes are documented below and forwarded as generic JSON objects. All client adapters share one WebSocket transport, and each live invocation suspends until `tool.result` or its deadline:

| Operation | `tool.call.started.payload.arguments` | Successful `tool.result.result` | Deadline |
| --- | --- | --- | --- |
| `user.ask` | `question` (required string) | `answer` (string) | 5 minutes |
| `device.media.open` | `query` (required string), `genre` (optional string) | `opened` (boolean), optional device-specific fields such as `contentId` | 1 minute |

For `device.media.open`, `status = "succeeded"` reports transport completion; `opened` says whether the device actually opened the media. Client-Souz threads use the backend's single request-scoped steerable skills graph and discover these operations through its Skill inventory.
