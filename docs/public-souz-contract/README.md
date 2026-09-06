# Client-Souz Contract

Draft public contract for Client integrations with Souz Cloud.

The canonical frame trace is [examples/happy-path.jsonl](examples/happy-path.jsonl). This document records the rules that are not obvious from that trace. [openapi.yaml](openapi.yaml) keeps the REST endpoint and reusable WebSocket frame schemas machine-readable.

Local API-client setup for the HTTP request and the trace's media scenario is in [postman/](postman/) and [bruno/](bruno/). The canonical trace also includes a complete web search exchange in a subsequent thread.

## Boundary

Client owns audio ingestion, external token validation, ASR, TTS, screen rendering, device actions, and delegated web search. The client can be a proxy server that runs its own agent and delegates fallback requests to Souz. Souz receives trusted `userId` values, recognized text, device metadata, and tool results. This API is only exposed inside a trusted environment, so the public contract does not require credentials.

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

`GET /v1/chats/{chatId}/threads/{threadId}?clientType=...` returns the current durable status for a public thread. Use it as a liveness probe when a socket is disconnected, when no event has arrived within the client's expected window, or when an idempotent retry returns a stored acknowledgement. The response includes `status`, `alive`, `acceptsInput`, timestamps, runtime lease expiry, and terminal `error` when present.

## WebSocket

Route: `/v1/chats/{chatId}/ws?clientType=...&afterSeq=...`

`afterSeq` is optional and exclusive. If omitted, Souz treats it as `0` and replays every durable event in the chat. On connect, Souz sends events with `seq > afterSeq` in order, then live frames. `seq` is chat-local, monotonic, and used for replay and deduplication. A separate `eventId` is not used.

`message.submit`, tool results, and cancellations for an active thread are owner-sticky. In multi-replica deployments, those frames must reach the Souz runtime owner that holds the live registry state; the current single-owner contract rejects them as `message_rejected` when they reach a process that only sees the durable running row. `history.append` remains chat-scoped and can be stored on any process. Durable replay and thread status remain available from any process.

Client frames:

- `message.submit`: `{kind, chatId, requestId, threadId?, payload}`. Submits user input for execution and retains explicit/implicit thread selection. Its role is inherently `user` and is not a field.
- `history.append`: `{kind, chatId, requestId, payload}`. Text content accepts `user` or `assistant`; `tool_exchange` content requires `assistant` and becomes a matched assistant tool request and tool result. History is supplied only with the next accepted `message.submit`.
- `tool.result`: `{kind, chatId, threadId, toolCallId, status, result|error}`. `status` is `succeeded`, `failed`, `cancelled`, or `timed_out`.
- `thread.cancel`: `{kind, chatId, requestId, threadId, reason?}`.

Souz frames:

- `ack`: acknowledgement for accepted or rejected client frames.
- `status` with `type = thread.status`: live-only current thread status sent after accepted `message.submit` and `thread.cancel` acknowledgements. This frame is not durable and is not replayed.
- `event` with `type = tool.call.started`: includes `threadId`, `toolCallId`, `target`, `name`, `arguments`, optional `deviceId`, and optional `deadlineAt`.
- terminal `event` with `type = thread.completed | thread.failed | thread.cancelled`.
- `event` with `type = message.created` and `threadId = null`: an out-of-band message pushed into this chat's history that did not originate from a thread this client started (e.g. forwarded here from another of the user's channels). Ordinary in-thread messages are not delivered on this stream.

Tool `target` is only `souz` or `client`. The connected Client side can be `backend` or `mobile_app`, but that does not create a third tool target.

Frame envelopes reject unknown fields. Tool arguments and results remain generic JSON. See [OpenAPI components](openapi.yaml) for exact field shapes.

## Threads

A thread is a task inside a chat. A chat can outlive many WebSocket connections and many threads.

An explicit `message.submit.threadId` continues that thread. When `threadId` is absent, Souz continues the chat's active thread; it creates a thread only when the chat has no active thread. An active thread that does not accept input rejects the submission without creating a replacement.

The acknowledgement returns:

- `thread.id`.
- `thread.created`: `true` when the originally acknowledged request created the thread, `false` for a continuation.
- `thread.status = running`.

Additional accepted submissions to a running thread append to its input log. The agent must observe every committed input before terminal state. A public `thread.started` event is not emitted because an ack with `thread.created = true` carries that state; live `thread.status` frames provide immediate non-replayable feedback.

Each thread has exactly one terminal event. If completion and cancellation race, first persisted terminal state wins. If `message.submit` commits before terminal, terminal output must account for it; if terminal commits first, the submission is rejected with `thread_already_terminal`.

## History

History belongs to the chat and has no thread identity. It is stored with its original user or assistant role without changing an execution, cancellation state, runtime lease, or active device context. Its acknowledgement has no execution fields; no thread status or durable event follows it.

History remains pending until the next accepted `message.submit`; it is not delivered to an active runtime on its own. The execute submission loads history ordered before its user-message row, preserves text roles, expands each tool exchange into a matched `RunSkillCommand` request and function result, and supplies that history followed by the execute input as one batch. The exchange `name` becomes the command's `skillId`. History ordered after that execute remains pending for a later submission.

The internal chat-local message sequence defines execute barriers and makes concurrent history and execute ordering authoritative. Storage does not require local runtime ownership, and the runtime owner catches up from durable messages when it accepts the next execute.

## Idempotency

`message.submit`, `history.append`, and `thread.cancel` use `(chatId, requestId)`. Same key, kind, and normalized payload returns the original result with `duplicate = true`; reusing the key with a different kind or payload returns rejected ack with `idempotency_conflict`. Message normalization includes client-supplied nullable `threadId`, content, device, and request metadata. History normalization includes role and content. Receipt replay precedes `message.submit` thread selection, while `history.append` bypasses thread selection entirely.

`tool.result` uses `(chatId, threadId, toolCallId)`. Repeating the same terminal result returns accepted ack with `duplicate = true` and does not append another event. Reusing the same key with a different terminal payload returns rejected ack with `duplicate = false` and `idempotency_conflict`.

Tool-result acknowledgements are outside the event sequence.

## Client operations

Client operations are backend-owned tool-backed Skills defined by indexed classpath `SKILL.md` resources. Their argument shapes are documented below and forwarded as generic JSON objects. All client adapters share one WebSocket transport, and each live invocation suspends until `tool.result` or its deadline:

| Operation | `tool.call.started.payload.arguments` | Successful `tool.result.result` | Deadline |
| --- | --- | --- | --- |
| `user.ask` | `question` (required string) | `answer` (string) | 5 minutes |
| `device.media.open` | `query` (required string), `genre` (optional string) | `opened` (boolean), optional device-specific fields such as `contentId` | 1 minute |
| `web.search` | `query` (required nonblank string) | `documents` (required array of objects with required `text` string and optional `title`/`url` strings) | 1 minute |

For `device.media.open`, `status = "succeeded"` reports transport completion; `opened` says whether the device actually opened the media. Client-Souz threads use the backend's single request-scoped steerable skills graph and discover these operations through its Skill inventory.

### Web search

Souz invokes `web.search` with `{"query":"Нечто 1982 режиссёр"}` through the existing `tool.call.started` event with `target = "client"`. The client proxy executes the search and returns a correlated `tool.result`. The event's `deviceId` identifies the latest accepted device context; the proxy performs the search on its behalf. Device capabilities remain metadata and do not gate this operation; there is no `web_search` capability.

The client uses the Web Search Plugin's Search API and owns its requests, credentials, and response normalization. It maps the query into the selected Search request format and returns only search evidence to Souz:

- For Search basic responses, take content from `payload.ask_gigachat.messages` entries with `role = "search_result"`. Do not forward plugin system/user messages or their instructions. If the content combines sources without reliable document boundaries, return it as one document's `text`.
- For Search function-result responses, decode the JSON string in `payload.function_result.content` and take its `documents`.
- Preserve source titles and URLs only when provided. Omit unavailable metadata; do not invent source boundaries, titles, or URLs.

Successful results use `{"documents":[{"text":"Режиссёр фильма «Нечто» (1982) — Джон Карпентер."}]}`. No matches is a successful `{"documents":[]}`. Souz treats document text as source data, not instructions, and cites only returned source metadata. A plugin or proxy failure uses `status = "failed"` with `error.code = "web_search_failed"` and a useful `error.message`. Deadline expiry uses the existing `client_tool_timed_out` behavior.

The reusable `WebSearchArguments`, `WebSearchResult`, and `WebSearchDocument` components in [openapi.yaml](openapi.yaml) document this operation's shapes. The transport forwards generic JSON and does not validate operation-specific argument or result schemas. The existing acknowledgement ordering, idempotency, cancellation, and reconnect replay rules apply.

Client-Souz execution catalogs expose `web.search` in the `WEB_SEARCH` Skill category and omit compiled `InternetSearch` when that client Skill is present. `InternetResearch` and `WebPageText` retain their existing selection rules; execution paths without the client Skill retain `InternetSearch`.
