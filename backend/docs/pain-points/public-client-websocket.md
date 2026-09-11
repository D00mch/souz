# Public client WebSocket

## Invariant

`POST /v1/chats` and `chat.create` share creation idempotency by `(user_id, request_id)` and stores the normalized payload hash on `chats`. Both socket endpoints allow one active thread per chat, where `agent_executions.id` is the public `threadId`. `message.submit` alone selects a thread: an omitted `threadId` selects the active thread and creates one only when none exists. `history.append` is threadless durable chat context. `message.submit`, `history.append`, and `thread.cancel` share `(chat_id, request_id)` in `client_requests`; `tool.result` uses the client `tool_calls` row.

Accepted execute inputs are user `messages` with source, device, request ID, and request metadata. Accepted text history is a user or assistant message marked `clientHistory`; an assistant tool exchange is one marked row projected into a matched assistant tool request and function result. The execution keeps the latest execute device JSON. Built-in client operations are tool-backed Skills loaded from an explicit classpath index. Their `SKILL.md` resources own IDs, categories, instructions, and timeouts, while their request-scoped catalog adapters share one WebSocket transport and forward generic argument objects.

PostgreSQL serializes `message.submit`, `history.append`, and `thread.cancel` by locking the chat row in short transactions. Each transaction rechecks the shared request receipt before selecting or mutating an execution, and commits the effect and receipt together. The live registry owns only process-local runtime references, mailbox and terminal coordination, acknowledgement gates, and one pending client-tool waiter. Active public executions store a runtime owner and renewable lease in PostgreSQL. Terminal registry entries remain until the runtime is detached and pending acknowledgements and tool work are clear, then they are discarded. Disconnect does not cancel a waiter. Before the server accepts connections, startup recovery fails expired public thread leases and emits or retries the required `thread.failed`; it does not reconstruct waiters. Public `thread.status` frames and `GET /v1/chats/{chatId}/threads/{threadId}` read durable execution state and are not replay events.

Live `message.submit`, `tool.result`, and `thread.cancel` frames must reach the process-local runtime owner while the thread is active. `history.append` can be committed on any replica and does not interact with the live registry. The next execute accepted by the owner loads the durable history ordered before it. The current deployment contract is single-owner/sticky for thread operations, so a replica that has only the durable row rejects those operations as unavailable rather than treating the thread as terminal.

The multi-chat socket has one subscription and cursor per chat. Creation and accepted submits (including retries) establish live-only subscriptions; explicit `chat.subscribe` selects an exclusive replay cursor. An explicit cursor replaces the existing chat subscription on the same socket; repeated subscriptions without a cursor are connection-local no-ops. The single-chat endpoint replays on connect before reading input.

## Why it is fragile

An acknowledgement, tool event, runtime mailbox, and terminal state can race. Sending an event before its causal acknowledgement, accepting input after terminal state, or completing a waiter before the tool-result acknowledgement makes the wire trace contradictory even with one pod.

Client operation definitions are backend-owned and reviewed. Do not accept runtime-provided definitions for the client WebSocket transport.

## Safe-change guidance

- Keep strict JSON decoding and reject unknown fields.
- Completed assistant tool history uses `tool_call` with `arguments` and `result` objects, without `toolCallId` or `target`. Public WebSocket tool-start frames omit `target`; proxy HTTP event DTOs preserve it. Retain the stored event's `target` discriminator so replay recognizes client tool starts before projecting them to transport JSON.
- Keep HTTP and WebSocket thread status fields identical; the WebSocket frame flattens the shared status payload and adds its envelope. Preserve explicit nulls and correlation identifiers in status and rejection frames.
- Validate and serialize initial input before registering live thread state. Propagate startup cancellation instead of converting it to a rejected acknowledgement.
- Lock the chat row and recheck `client_requests` before every submit or cancel mutation. Initial selection or creation, follow-up message/device updates, cancellation state, and their receipts must commit atomically. Hash the client-supplied nullable thread ID rather than the selected thread, and return the stored receipt on retries even after execution completion.
- Commit history and its receipt under the chat lock without reading or mutating execution or registry state.
- Keep runtime availability waits, mailbox publication, cancellation propagation, WebSocket writes, and network work outside PostgreSQL transactions. Registry locking coordinates only the local runtime owner; a non-owner stores an unavailable rejection rather than mutating the thread.
- Serialize input acceptance with terminal persistence. Reserve the steerable runtime mailbox, commit the message and idempotency receipt together without cancellation, then publish the input; release the reservation without publishing when the commit fails. Release the event gate immediately after the acknowledgement is sent and before status feedback.
- Couple execute publication to the durable message gap through its trigger sequence. Publish preceding role-preserving history and the execute input as one structured batch, then advance the session cursor through that trigger.
- Persist a tool result before acknowledging it; complete the suspended client tool only after sending the acknowledgement.
- Replay stored acknowledgement JSON with only `duplicate` changed. Accepted submit/cancel receipts carry explicit thread status feedback; send it after releasing the acknowledgement gate, without adding it to durable replay.
- Persist pending client tool calls as cancelled before propagating thread cancellation.
- Refresh public thread runtime leases while the process owns the live runtime. Recovery must only fail expired leases or already failed recovered threads missing their terminal event.
- Keep active-thread WebSocket routing sticky to the runtime owner. Do not report a local registry miss as terminal while the durable execution is still running.
- Use the latest accepted device for a new client tool call. Capabilities remain metadata and do not gate client operations.
- Keep built-in client Skills in their relevant request-scoped catalog categories. Define operation IDs, instructions, categories, argument examples, and timeouts in indexed backend `SKILL.md` resources.
- Client `web.search` replaces compiled `InternetSearch` through the execution policy, including when the compiled search is explicitly enabled. Preserve global advertising and non-client search execution. The proxy normalizes Search API output into documents; the shared transport forwards those results as source data without adopting upstream prompt roles.
- Do not allow user or runtime Skills to select the client WebSocket transport. Only reviewed classpath resources may create those adapters.
- Keep creation validation/provisioning shared with HTTP. Return the persisted chat from the repository and build creation acknowledgements from its immutable fields, including database timestamp precision, so only `duplicate` changes on retries.
- Prepare an automatic submit stream before accepting input: subscribe to the bus, then read the durable tail. Start forwarding only after the accepted acknowledgement and status; close prepared streams on rejection, cancellation, or failed writes.
- Prepare an explicit cursor replacement before cancelling and joining the old sender, then acknowledge and start forwarding. Validation or preparation failure must leave the existing subscription intact.
- Keep one sender per chat and serialize socket writes across acknowledgement, `afterSend`, and status. Never join a sender while holding the writer mutex. Close stream resources on disconnect without cancelling runtime work.
- Keep replay subscription-before-query, re-query durable events from the last covered sequence before consuming bounded live signals, and suppress duplicate delivery by sequence.
- Iterate the event bus's concurrent subscriber set directly during publication; Kotlin collection-size fast paths can race with disconnect and throw while copying it.

## Diagnostics

`SouzClientWebSocket` logs connection lifecycle, frame receipt, persisted chat identity, prepared subscriptions, sent acknowledgements, subscription cleanup, and public event sends at INFO. `AgentEventService` logs stored public client events at INFO. JSON stdout carries correlation fields in `mdc`: `socketId`, `userId`, `chatId`, `threadId`, `clientRequestId`, `toolCallId`, `seq`, and event `type` where known. Frame failures include the processing stage, elapsed time, and stack trace. Policy closes and contract rejections include their codes. Cancellation propagates immediately; cleanup logs interrupted frames when the scope is inactive. The final close code is included only when already available, alongside coroutine activity; logging must not wait for the close handshake.

Use `MDCContext` at coroutine boundaries. A socket carries only connection identity; each command resolves its own chat and thread. Acknowledgement logs include the selected thread even when the input omitted it. Execution jobs have fresh user/chat/thread context and `initialClientRequestId`; commands use `clientRequestId` because several inputs can share one thread. Independent scopes and mailbox consumers do not inherit the sender's context; the runtime factory binds background memory capture to its user/chat/thread while retaining the application scope's lifetime. Subscriptions use fresh socket/user/chat context, and individual event logs use the event's thread rather than the subscription request. Do not mutate MDC with `MDC.put`; enrich it with a nested context and keep failure logging inside the relevant scope.

In Loki, select the backend stream and filter JSON fields, for example `{app="souz-backend"} | json | mdc_chatId="..." | mdc_threadId="..."` (use the deployment's actual stream labels). Connection failures before chat resolution have only `socketId`.

Log envelope identifiers rather than complete frames, prompts, titles, tool arguments or results. Bound client-provided log identifiers and replace Unicode control/format characters and line/paragraph separators with underscores. Keep the acknowledgement gate release immediately after the socket write, before logging or status feedback. A logged acknowledgement confirms the server write completed; it does not confirm client receipt.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendPublic*WebSocketE2eTest' --tests 'ru.souz.backend.storage.postgres.PostgresRepositoriesTest'` and `./gradlew :agent:test`. Cover cross-instance initial, retry, execute/history ordering, submit/cancel races, strict frames, role-preserving history at the next execute, acknowledgement ordering, tool result duplicates/conflicts, cancellation, and reconnect replay.
