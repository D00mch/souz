# Client-Souz Contract

Draft contract for Souz Cloud. Exact fields are in [OpenAPI](openapi.yaml); [happy-path.jsonl](examples/happy-path.jsonl) shows two chats, retries, client tools and replay. Local setup: [Postman](postman/) / [Bruno](bruno/).

## Connection

Connect to `/v1/ws?clientType=backend`. One WebSocket can serve multiple users' chats; chat-scoped frames carry `chatId`. Each chat's `clientType` must match the connection.

This API requires a trusted environment: the client validates identity upstream and supplies `userId`; the API requires no credentials. Submit device ownership must match the chat.

A **chat** stores history; a **thread** is a task inside it. A **subscription** delivers a chat's events over the existing socket. It opens no extra connection and does not own the execution.

## Commands

| Kind | Purpose |
| --- | --- |
| `chat.create` | Create or retrieve a chat with `requestId` and `payload:{userId,title?}`. ACK returns `chatId`; `clientType` comes from the socket. |
| `chat.subscribe` | Replay and receive live events using `chatId`, `requestId` and optional `afterSeq`. |
| `message.submit` | Submit user input. Explicit `threadId` continues that thread; omission selects the active thread or creates one. |
| `history.append` | Store context consumed by the next accepted submit, without executing input. |
| `tool.result` | Resolve a call by `chatId`, `threadId` and `toolCallId`. |
| `thread.cancel` | Cancel a thread. |

History, tool results and cancellation neither require nor initiate subscriptions. History preserves roles and tool exchanges; it does not reach a running task until another submit. A thread that cannot accept input rejects submissions; each thread has one terminal event.

Return `tool.result` for client-targeted calls, respecting `deadlineAt` when present. The example covers `user.ask`, `device.media.open` and `web.search`; argument/result shapes are documented in the schemas and trace.

Active-thread submit/tool/cancel operations must reach the runtime owner in multi-replica deployments. Durable replay and thread status can be read from any process.

## Subscriptions and reconnect

Public events are stored in the database independently of subscriptions. A subscription keeps a temporary cursor; **Souz does not persist which events the client received or processed**.

A new socket has no subscriptions. Successful creation or an accepted submit automatically subscribes an unsubscribed chat to **live events only**, including retries. Events caused by that submit are included; earlier events require explicit replay:

```json
{"kind":"chat.subscribe","chatId":"10000000-0000-4000-8000-000000000001","requestId":"restore-A","afterSeq":5}
```

| `chat.subscribe` | Result |
| --- | --- |
| Explicit `afterSeq:N` | Replace this chat's subscription, replay `seq > N`, then continue live; `duplicate:false`. |
| Cursor omitted, not subscribed | Replay from `0`, then continue live; `duplicate:false`. |
| Cursor omitted, already subscribed | Keep the stream without replay; `duplicate:true`. |

The cursor must be a nonnegative integer. An explicit cursor always requests replay, even with a repeated `requestId`; other chats are unaffected. Subscribing does not execute input.

Disconnect closes subscriptions, preserving chats, stored events, executions and pending tools; tool deadlines still apply. Subscriptions have no TTL and survive thread completion. There is no `chat.unsubscribe`.

After reconnecting:

1. Restore each desired chat with `chat.subscribe`, passing its last successfully processed `seq` as `afterSeq`.
2. Process missed events followed by live events; save progress and deduplicate by `(chatId, seq)`.

Events saved during disconnection or recovery remain available. Reopening the socket or retrying a submit alone does not recover missed events.

## Delivery and retries

Souz sends an `ack` before events caused by a command and before subscription replay. Accepted submit/cancel also receive live `thread.status` feedback after the ACK. ACKs and status are not replayed.

Durable public events are `tool.call.started`, `thread.completed|failed|cancelled`, and out-of-band `message.created` with `threadId:null`. Ordinary in-thread message events are excluded. Events are ordered within each chat; chats may interleave, and filtered internal events leave valid sequence gaps.

| Operation | Idempotency key |
| --- | --- |
| HTTP creation / `chat.create` | Shared `(userId, requestId)` |
| `message.submit`, `history.append`, `thread.cancel` | Shared `(chatId, requestId)` |
| `tool.result` | `(chatId, threadId, toolCallId)` |

The same key, operation and normalized payload return the original result with `duplicate:true`, without repeating execution. Changes conflict with `idempotency_conflict`. Creation compares `clientType` and `title`; tool results compare terminal status and payload. `chat.subscribe.requestId` is only for correlation.

Frame envelopes reject unknown fields; tool arguments/results are generic JSON. Malformed JSON and unsupported kinds close the socket; recoverable errors receive correlated rejection ACKs.

## Other endpoints

- `POST /v1/chats`: HTTP creation for `backend` or `mobile_app`, sharing WebSocket creation idempotency.
- `GET /v1/chats/{chatId}/threads/{threadId}?clientType=...`: durable status and liveness.
- `/v1/chats/{chatId}/ws?clientType=...&afterSeq=...`: single-chat socket for either client type. Replays from the cursor (default `0`) before processing input; does not accept `chat.create` or `chat.subscribe`.
