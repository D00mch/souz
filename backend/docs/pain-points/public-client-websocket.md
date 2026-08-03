# Public client WebSocket

## Invariant

`POST /v1/chats` is idempotent by `(user_id, request_id)` and stores the normalized payload hash on `chats`. The chat-scoped socket accepts one active thread, where `agent_executions.id` is the public `threadId`. `message.submit` and `thread.cancel` share `(chat_id, request_id)` in `client_requests`; `tool.result` uses the client `tool_calls` row.

Accepted user inputs are `messages` with `inputSeq`, source, device, request ID, and request metadata. The execution keeps the latest revision and device JSON. The request-scoped `client.websocket` Skill loads its instructions from `SKILL.md`, so the Skills graph can send named operations with JSON arguments through `RunSkillCommand`.

The live registry owns only process-local runtime references, acknowledgement gates, and one pending client-tool waiter. A single coroutine mutex protects its state. Active public executions store a runtime owner and renewable lease in PostgreSQL. Terminal entries remain until the runtime is detached and pending acknowledgements and tool work are clear, then they are discarded. Disconnect does not cancel a waiter. Before the server accepts connections, startup recovery fails expired public thread leases and emits or retries the required `thread.failed`; it does not reconstruct waiters. Public `thread.status` frames and `GET /v1/chats/{chatId}/threads/{threadId}` read durable execution state and are not replay events.

## Why it is fragile

An acknowledgement, tool event, runtime mailbox, and terminal state can race. Sending an event before its causal acknowledgement, accepting input after terminal state, or completing a waiter before the tool-result acknowledgement makes the wire trace contradictory even with one pod.

## Safe-change guidance

- Keep strict JSON decoding and reject unknown fields.
- Serialize the shared `message.submit` and `thread.cancel` receipt check and acceptance in the single backend process.
- Serialize input acceptance with terminal persistence. Commit the message and revision only when `submitToActiveRun` accepts it; release its event gate only after the acknowledgement is sent.
- Persist a tool result before acknowledging it; complete the suspended Skill only after sending the acknowledgement.
- Send live `thread.status` feedback after accepted submit/cancel acknowledgements without adding it to durable replay.
- Persist pending client tool calls as cancelled before propagating thread cancellation.
- Refresh public thread runtime leases while the process owns the live runtime. Recovery must only fail expired leases or already failed recovered threads missing their terminal event.
- Use the latest accepted device for a new client tool call. Capabilities remain metadata and do not gate client operations.
- Keep `client.websocket` in the request-scoped tool catalog rather than adding it to the Skills graph core-tool list. Describe operation names and payloads in its `SKILL.md`, not Kotlin tool definitions.
- Keep replay subscription-before-query, re-query durable events from the last covered sequence before consuming bounded live signals, and suppress duplicate delivery by sequence.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.http.BackendPublicClientContractRouteTest'` and `./gradlew :agent:test`. Cover create idempotency, strict frames, second input during an active run, acknowledgement ordering, tool result duplicates/conflicts, cancellation, and reconnect replay.
