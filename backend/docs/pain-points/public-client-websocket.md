# Public client WebSocket

## Invariant

`POST /v1/chats` is idempotent by `(user_id, request_id)` and stores the normalized payload hash on `chats`. The chat-scoped socket accepts one active thread, where `agent_executions.id` is the public `threadId`. `message.submit` alone selects a thread: an omitted `threadId` selects the active thread and creates one only when none exists. `history.append` is threadless durable chat context. `message.submit`, `history.append`, and `thread.cancel` share `(chat_id, request_id)` in `client_requests`; `tool.result` uses the client `tool_calls` row.

Accepted execute inputs are user `messages` with `inputSeq`, source, device, request ID, and request metadata. Accepted text history is a user or assistant message marked `clientHistory`, without `inputSeq`; an assistant tool exchange is one marked row projected into a matched assistant tool request and function result. The execution keeps the latest execute revision and device JSON. Built-in client operations are tool-backed Skills loaded from an explicit classpath index. Their `SKILL.md` resources own IDs, categories, instructions, and timeouts, while their request-scoped catalog adapters share one WebSocket transport and forward generic argument objects.

PostgreSQL serializes `message.submit`, `history.append`, and `thread.cancel` by locking the chat row in short transactions. Each transaction rechecks the shared request receipt before selecting or mutating an execution, and commits the effect and receipt together. The live registry owns only process-local runtime references, mailbox and terminal coordination, acknowledgement gates, and one pending client-tool waiter. Active public executions store a runtime owner and renewable lease in PostgreSQL. Terminal registry entries remain until the runtime is detached and pending acknowledgements and tool work are clear, then they are discarded. Disconnect does not cancel a waiter. Before the server accepts connections, startup recovery fails expired public thread leases and emits or retries the required `thread.failed`; it does not reconstruct waiters. Public `thread.status` frames and `GET /v1/chats/{chatId}/threads/{threadId}` read durable execution state and are not replay events.

Live `message.submit`, `tool.result`, and `thread.cancel` frames must reach the process-local runtime owner while the thread is active. `history.append` can be committed on any replica and does not interact with the live registry. The next execute accepted by the owner loads the durable history ordered before it. The current deployment contract is single-owner/sticky for thread operations, so a replica that has only the durable row rejects those operations as unavailable rather than treating the thread as terminal.

## Why it is fragile

An acknowledgement, tool event, runtime mailbox, and terminal state can race. Sending an event before its causal acknowledgement, accepting input after terminal state, or completing a waiter before the tool-result acknowledgement makes the wire trace contradictory even with one pod.

Client operation definitions are backend-owned and reviewed. Do not accept runtime-provided definitions for the client WebSocket transport.

## Safe-change guidance

- Keep strict JSON decoding and reject unknown fields.
- Validate and serialize initial input before registering live thread state. Propagate startup cancellation instead of converting it to a rejected acknowledgement.
- Lock the chat row and recheck `client_requests` before every submit or cancel mutation. Initial selection or creation, follow-up message/revision/device updates, cancellation state, and their receipts must commit atomically. Hash the client-supplied nullable thread ID rather than the selected thread, and return the stored receipt on retries even after execution completion.
- Commit history and its receipt under the chat lock without reading or mutating execution or registry state.
- Keep runtime availability waits, mailbox publication, cancellation propagation, WebSocket writes, and network work outside PostgreSQL transactions. Registry locking coordinates only the local runtime owner; a non-owner stores an unavailable rejection rather than mutating the thread.
- Serialize input acceptance with terminal persistence. Reserve the steerable runtime mailbox, commit the message, revision, and idempotency receipt together without cancellation, then publish the input; release the reservation without publishing when the commit fails. Release the event gate immediately after the acknowledgement is sent and before status feedback.
- Couple execute publication to the durable message gap through its trigger sequence. Publish preceding role-preserving history and the execute input as one structured batch, then advance the session cursor through that trigger.
- Persist a tool result before acknowledging it; complete the suspended client tool only after sending the acknowledgement.
- Send live `thread.status` feedback after accepted submit/cancel acknowledgements without adding it to durable replay.
- Persist pending client tool calls as cancelled before propagating thread cancellation.
- Refresh public thread runtime leases while the process owns the live runtime. Recovery must only fail expired leases or already failed recovered threads missing their terminal event.
- Keep active-thread WebSocket routing sticky to the runtime owner. Do not report a local registry miss as terminal while the durable execution is still running.
- Use the latest accepted device for a new client tool call. Capabilities remain metadata and do not gate client operations.
- Keep built-in client Skills in their relevant request-scoped catalog categories. Define operation IDs, instructions, categories, argument examples, and timeouts in indexed backend `SKILL.md` resources.
- Do not allow user or runtime Skills to select the client WebSocket transport. Only reviewed classpath resources may create those adapters.
- Keep replay subscription-before-query, re-query durable events from the last covered sequence before consuming bounded live signals, and suppress duplicate delivery by sequence.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendPublicWebSocketE2eTest' --tests 'ru.souz.backend.storage.postgres.PostgresRepositoriesTest'` and `./gradlew :agent:test`. Cover cross-instance initial, retry, execute/history ordering, submit/cancel races, strict frames, role-preserving history at the next execute, acknowledgement ordering, tool result duplicates/conflicts, cancellation, and reconnect replay.
