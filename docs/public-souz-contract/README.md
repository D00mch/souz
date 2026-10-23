# Client-Souz Contract

Draft contract for Souz Cloud. Exact fields are in [OpenAPI](openapi.yaml); [happy-path.jsonl](examples/happy-path.jsonl) shows two users' chats, a creation retry, history before and during execution, continued and new threads, client tools, a forwarded message, unsubscribe and resubscribe with replay per chat. Each line is a complete WebSocket frame: Souz sends `ack`, `status` and `event`; the client sends the other kinds. Local setup: [Postman](postman/) / [Bruno](bruno/).

Orion handles server-driven [`prompt.personality.set`](../../backend/docs/personality-settings.md) snapshots
on subscription/reconnection and after a user style change. These live commands use `seq:null` and the
normal `tool.result` acknowledgement, independently of the tool catalogue.

## Connection

Connect to `/v1/ws?clientType=backend`. One WebSocket can serve multiple users' chats; chat-scoped frames carry `chatId`. Each chat's `clientType` must match the connection.

This API requires a trusted environment: the client validates identity upstream and supplies `userId`; the API requires no credentials. Submit device ownership must match the chat.

A **chat** stores history; a **thread** is a task inside it. A **subscription** delivers a chat's events over the existing socket. It opens no extra connection and does not own the execution.

## Commands

| Kind | Purpose |
| --- | --- |
| `chat.create` | Create or retrieve a chat with `requestId` and `payload:{userId,title?,tools?}`. ACK returns `chatId`; `clientType` comes from the socket. |
| `chat.subscribe` | Replay and receive live events using `chatId`, `requestId` and optional `afterSeq`. |
| `chat.unsubscribe` | Close this socket's subscription to `chatId`, with a correlated `requestId` ACK. |
| `message.submit` | Submit user input. Explicit `threadId` continues that thread; omission selects the active thread or creates one. |
| `history.append` | Store context consumed by the next accepted submit, without executing input. |
| `tool.result` | Resolve a call by `chatId`, `threadId` and `toolCallId`. |
| `thread.cancel` | Cancel a thread. |

History, tool results and cancellation neither require nor initiate subscriptions. History preserves roles and completed tool calls; it does not reach a running task until another submit. A thread that cannot accept input rejects submissions; each thread has one terminal event.

Assistant tool history uses `content: {"type":"tool_call","name":"weather","arguments":{},"result":{"temperature_c":25.1}}`. Both `arguments` and `result` are required JSON objects; history content has no `toolCallId` or `target`. Send memory retrieval results through this same shape, preserving the tool name, arguments and result. Do not copy tool results into text history: text is eligible for memory extraction.

With `HINDSIGHT_API_URL` configured and `SOUZ_FEATURE_WS_AUTOMATIC_MEMORY_RECALL=true`, Souz retrieves memory once before the first LLM call of each new thread. The flag defaults to `false` and controls all backend executions. Client-supplied memory history remains available in either mode. Tool loops, duplicate submits, reconnects and active-thread continuation do not repeat automatic recall. The agent can invoke `SearchMemory` explicitly in either mode. With Hindsight enabled, accepted text history also enters background memory capture. See [external memory](../../backend/docs/external-memory.md) for retention, acknowledgement timing, attribution, privacy and recovery rules.

## Client tool catalogue

HTTP `POST /v1/chats` and WS `chat.create.payload` accept optional `tools`, an array of `{name, description, parameters}` definitions. Bundled client Skills are always available: `user.ask`, `device.media.open`, `device.mcp.list_devices`, `device.mcp.list_tools`, `device.mcp.call_tool`, `web.search`, and `orion.tools.list`. Orion command tools come from declarations or discovery. Declarations override matching defaults. Missing `tools` and `[]` are equivalent; explicit `null` is invalid. Server tools keep their existing policy.

Delegated client Skills also use `RunSkillCommand`; their exact names and full schemas are included in the child's instructions. Only explicitly selected Skills are callable. Local discovery preserves schemas in full; the selected model's context window must still fit them.

Creation declarations are validated and stored atomically with the chat and remain immutable. Both creation paths share `(userId, requestId)` idempotency: definitions are sorted by name, schema property keys are sorted, and `required` and `enum` arrays are deduplicated and sorted. Names and descriptions are preserved exactly. Changed creation declarations conflict; discovered tools do not change those declarations or receipts. Pre-existing empty-catalogue creation retries remain valid.

Invoke `orion.tools.list` through `RunSkillCommand` with `{}`, adding routing `channelId` when targeting another connected device. Orion returns `{"tools":[...]}` using the same definitions and limits as creation, excluding `orion.tools.list`. Souz validates the whole response before saving and exposing the tools. Invalid catalogues return `invalid_client_tool_catalogue`; storage failures return `client_tool_failed`. Failures preserve the previous catalogue.

Each source conversation remembers one discovered catalogue and its supplying channel across turns and backend restarts. Discovered definitions override matching declarations/defaults and are callable immediately. Successful discovery replaces the previous discovered catalogue, including when selecting another channel; `{"tools":[]}` restores the declared/default baseline. Refresh is explicit, with no expiry. A disconnected or archived target requires selecting a live channel and rediscovering. Discovered tools reject a different `channelId` with `client_context_mismatch`. The parent discovers before delegating selected tools; the discovery tool itself is not delegable. Separate webhook-created conversations do not share discoveries.

Limits: 256 tools; 1 MiB of serialized definitions; unique names matching `[A-Za-z][A-Za-z0-9_.-]{0,63}`; nonblank descriptions of at most 16,384 characters; schema depth at most 16 below the root. Core Skill and server tool names are reserved. Validation rejects the entire creation with `invalid_request`.

Schema validation messages identify the first failing rule and its JSON Pointer relative to the creation payload, such as `/tools/0/parameters/properties/users/items`. Tool indexes refer to the submitted array before normalization; property names escape `~` as `~0` and `/` as `~1`.

`parameters` uses an object root with `type`, optional `properties`, `required`, and boolean `additionalProperties`. Nested properties support `type`, `description`, nonempty string `enum`, object `properties`/`required`/`additionalProperties`, and array `items`. Types are `object`, `array`, `string`, `integer`, `number`, `boolean`, and `null`. Arrays require `items`; required names must be declared properties. Other schema keywords are rejected, not discarded. Arguments are checked against this schema before dispatch; invalid arguments return `mcp_invalid_arguments` through generic Skill execution.

Client tools use `GetSkillByName` and `RunSkillCommand`. Declared and discovered definitions retain their exact wire names and normalized schemas; known names also receive bundled usage instructions appended to their description. Defaults use open object schemas except `orion.tools.list`, which accepts an empty object. Parents and selected subagents receive that guidance; persisted declarations and creation hashes retain the original descriptions. `user.ask` uses `CHAT`, `web.search` uses `WEB_SEARCH`, and other client tools use `APPLICATIONS`. Successful results must be JSON objects; malformed success output becomes `invalid_tool_result` for the model. Custom tools have a one-minute deadline. Known Skill names use bundled deadlines: five minutes for `user.ask`, 30 seconds for `device.mcp.list_devices`, `device.mcp.list_tools`, and `orion.tools.list`, and one minute for the remaining Skills. `channelId` is reserved routing metadata, removed before schema validation and device dispatch; it cannot be declared as a tool parameter. Orion owns device binding; creation adds no device fields or binding checks.

Every public `tool.call.started` requests client execution and omits `target`. Return `tool.result`, respecting `deadlineAt` when present. The example covers `user.ask`, `device.media.open` and `web.search`; argument/result shapes are documented in the schemas and trace.

Cross-channel client Skills accept `channelId` from `ListActiveChannels`. The target must be an owned,
unarchived device channel with a live subscription on the caller's backend process. Cross-channel
`user.ask` also requires the [destination device context](#destination-device) described below. Souz removes
`channelId` from the device arguments. These `tool.call.started` events have `seq:null` and are never
replayed. Their `threadId` is a correlation UUID, not a persisted target thread: echo it in `tool.result`,
but do not query or cancel it as a thread. The target can run its own thread concurrently.

Cross-channel results resume the caller after the ACK is sent. They have no durable idempotency
receipt: the first result reserved before `deadlineAt` wins and waits for its ACK even beyond that
deadline. Further results, including those after timeout or caller cancellation, are rejected as
`tool_call_not_found`. A failed ACK write releases the caller with `client_tool_failed`. Disconnect
does not replay the call; if no result arrives before `deadlineAt`, the caller receives `client_tool_timed_out`.

Active-thread submit/tool/cancel operations must reach the runtime owner in multi-replica deployments. Durable replay and thread status can be read from any process.

## Destination device

`assistant.message`, `thread.completed`, `message.created`, and `tool.call.started` with `payload.name:"user.ask"` require non-null `payload.deviceId`, a nonblank string identifying the device that should receive the user-facing content or question. `payload.deviceType` is optional; when present, it must be non-null and one of `tv_box`, `smart_speaker`, `smartphone`, or `unknown`.

For forwarded `message.created` and cross-channel `user.ask`, the destination is the device from the target chat's most recently accepted new `message.submit`, captured when the event is created. This context is retained after thread completion and reconnects. Rejected submissions and idempotent retries do not replace it. Chat creation, subscription and `history.append` do not establish device context; a chat that has never accepted a submit is ineligible for these operations, even when listed by `ListActiveChannels` or subscribed.

If the target has no device context, fail the originating operation before creating a target message, pending question or event. Cross-channel `user.ask` returns `client_context_missing` to its caller; message forwarding returns `success:false` with a `reason` explaining that destination device context is unavailable. These are caller-side tool results, not frames sent to the target socket. Do not substitute the source device or a fabricated ID. Other cross-channel tools retain their existing eligibility rules.

| Target chat state | Forwarded message or cross-channel `user.ask` |
| --- | --- |
| Created and subscribed, no accepted submit | Fail without a target message or event. |
| Accepted a submit from device A; its thread has finished | Use device A, subject to the operation's other delivery requirements. |
| Later accepted a new submit from device B | Use device B for new events; replay preserves device A on earlier events. |

Durable replay preserves the event's original device values, including omission of `deviceType`. Other tool calls retain an optional, nullable `payload.deviceId` and have no `deviceType` field.

## Intermediate assistant messages

Souz sends one initial waiting phrase through `assistant.message` after turn classification, unless the turn is simple and needs no memory recall. This phrase is independent of `narrateSteps`, `streamingMessages` and `showToolEvents`. Duplicate submissions, reconnects, tool loops and active-thread continuations do not repeat it. It is absent from history and replay.

Other progress is opt-in through the trusted settings API: `PATCH /v1/settings` with `{"narrateSteps":true}`. Settings responses expose the effective value, defaulting to `false`. The preference controls the additive RU/EN prompt instruction and live WebSocket, Telegram, and VK delivery independently of `streamingMessages` and `showToolEvents`. Each execution snapshots it, including option continuations; settings changes affect subsequent executions.

`assistant.message` carries one complete, nonblank text block in `payload:{"content":"Let me check.","deviceId":"device-tv-456","deviceType":"tv_box"}`. An accepted LLM response containing tool calls can produce several such events, in the original block order, before those tools execute. Separate blocks stay separate, including repeated text. Streaming providers assemble the full response and pass the agent's acceptance check first; stream chunks are not messages. Reasoning, discarded attempts and final answers do not produce these events.

The envelope has `kind:"event"`, `type:"assistant.message"`, `seq:null`, `chatId`, the active `threadId`, and `createdAt`. On the submitting connection, the originating client request's ACK precedes its assistant events. These events are informational: send neither an ACK nor `tool.result`. Tool execution does not wait for receipt or speech synthesis. Continue waiting for `thread.completed`, `thread.failed`, or `thread.cancelled`; the final answer is only in `thread.completed.payload.response`.

Progress is live-only and best-effort. Current subscribers may receive it; disconnects, bounded-queue overflow, or durable catch-up overtaking queued progress can discard it. Souz never stores or replays these events, and does not add separate chat transcript rows. Intermediate text remains in the agent's existing conversation history. `seq:null` does not advance `afterSeq` and cannot be deduplicated by `(chatId, seq)`; do not collapse separate blocks with identical content.

## Subscriptions and reconnect

Durable public events are stored in the database independently of subscriptions. Live-only assistant messages and cross-channel tool starts are not stored. A subscription keeps a temporary durable cursor; **Souz does not persist which events the client received or processed**.

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

`chat.unsubscribe` requires an existing chat whose `clientType` matches the socket and a nonblank `requestId`:

```json
{"kind":"chat.unsubscribe","chatId":"10000000-0000-4000-8000-000000000001","requestId":"leave-A"}
```

The accepted ACK has `type:"chat.unsubscribe"` and `duplicate:false` when a subscription was closed, or `duplicate:true` when none existed. The sender and event stream are closed before the ACK: an event already in flight may precede it, but no events from that subscription follow it. Other chats and connections are unaffected. Invalid requests leave subscriptions intact.

Unsubscribe and disconnect preserve chats, history, stored events, executions and pending tools; tool deadlines still apply. They release subscription resources without unloading an active agent runtime. Subscriptions have no TTL and survive thread completion until explicitly closed. A later `chat.subscribe` restores replay using `afterSeq` as above. Successful creation or an accepted submit, including retries, restores a live-only subscription after unsubscribe; a rejected submit does not.

After reconnecting:

1. Restore each desired chat with `chat.subscribe`, passing its last successfully processed `seq` as `afterSeq`.
2. Process missed events followed by live events; save the durable cursor and deduplicate durable events by `(chatId, seq)`. Live-only tool starts have `seq:null`; execute them once per `(chatId, threadId, toolCallId)` without updating the replay cursor. `assistant.message` also has `seq:null`, requires no reply, and never appears in replay.

Events saved during disconnection or recovery remain available. Reopening the socket or retrying a submit alone does not recover missed events.

## Delivery and retries

On the connection processing a command, Souz sends its `ack` and any submit/cancel `thread.status` feedback before subsequent events. An explicit subscription ACK precedes its replay. Other connections, cross-channel commands, and reconnect replay proceed independently and may precede a retried ACK. Execution and durable event storage continue after disconnect without waiting for an ACK retry. Retries return the stored receipt with `duplicate:true`; ACKs and status are not replayed.

Durable public events are same-thread `tool.call.started`, `thread.completed|failed|cancelled`, and out-of-band `message.created` with `threadId:null`. Ordinary in-thread transcript events are excluded. Durable events are sequenced within each chat; chats may interleave, and filtered internal events leave valid sequence gaps. Live-only assistant blocks preserve their relative order when delivered, but stale blocks may be dropped during durable catch-up.

| Operation | Idempotency key |
| --- | --- |
| HTTP creation / `chat.create` | Shared `(userId, requestId)` |
| `message.submit`, `history.append`, `thread.cancel` | Shared `(chatId, requestId)` |
| `tool.result` | `(chatId, threadId, toolCallId)` |

For durable operations, the same key, operation and normalized payload return the original result with `duplicate:true`, without repeating execution. Changes conflict with `idempotency_conflict`. Creation compares `clientType`, `title`, and normalized `tools` (omitted tools and `[]` are equivalent); tool results compare terminal status and payload. For `chat.subscribe` and `chat.unsubscribe`, `requestId` is only for correlation; each request applies to the current connection state.

Frame envelopes reject unknown fields; tool arguments/results are generic JSON. Malformed JSON and unsupported kinds close the socket; recoverable errors receive correlated rejection ACKs.

JSON decoding rejection ACKs include `error.details` with a JSON Pointer `path` (empty for the root), `reason`, and optional `actual` and `expected`. Reasons distinguish unknown fields/types, missing fields, forbidden nulls, type mismatches, and invalid values. For example, an unsupported history content type produces `{"path":"/payload/content/type","reason":"unknown_type","actual":"tool_exchange","expected":["text","tool_call"]}`. Field names and type discriminators are bounded and sanitized; other submitted values are represented only by JSON type.

## Other endpoints

- `POST /v1/chats`: HTTP creation for `backend` or `mobile_app`, sharing WebSocket creation idempotency.
- `GET /v1/chats/{chatId}/threads/{threadId}?clientType=...`: durable status and liveness.
- `/v1/chats/{chatId}/ws?clientType=...&afterSeq=...`: single-chat socket for either client type. Replays from the cursor (default `0`) before processing input; does not accept `chat.create`, `chat.subscribe` or `chat.unsubscribe`.

Server-configured model names from bootstrap are accepted in `payload.meta.model`; see [named LLM connections](../../backend/docs/named-llm-connections.md).
