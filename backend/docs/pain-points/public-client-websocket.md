# Public client WebSocket

## Invariant

`POST /v1/chats` is idempotent by `(user_id, request_id)` and stores the normalized payload hash on `chats`. The chat-scoped socket accepts one active thread, where `agent_executions.id` is the public `threadId`. `message.submit` and `thread.cancel` share `(chat_id, request_id)` in `client_requests`; `tool.result` uses the client `tool_calls` row.

Accepted user inputs are `messages` with `inputSeq`, source, device, request ID, and request metadata. The execution keeps the latest revision and device JSON. `BackendClientSkills` loads reviewed client bundles from `skills/client` and exposes request-scoped client adapters for exact skill resolution. Their IDs, categories, timeouts, and instructions come from `SKILL.md`, while request-scoped adapters share one WebSocket transport and remain outside the direct agent catalog. The request kernel receives user registry bundles plus an ID-only inventory decorator for enabled client Skills.

Client Skill IDs must not collide with ordinary direct tools; the backend fails request runtime construction if they do. Enabled backend client adapters win exact-ID collisions with user-installed bundles through compiled-tool precedence.

The live registry owns only process-local runtime references, acknowledgement gates, and one pending client-tool waiter. A single coroutine mutex protects its state. Active public executions store a runtime owner and renewable lease in PostgreSQL. Terminal entries remain until the runtime is detached and pending acknowledgements and tool work are clear, then they are discarded. Disconnect does not cancel a waiter. Before the server accepts connections, startup recovery fails expired public thread leases and emits or retries the required `thread.failed`; it does not reconstruct waiters. Public `thread.status` frames and `GET /v1/chats/{chatId}/threads/{threadId}` read durable execution state and are not replay events.

Live `message.submit`, `tool.result`, and `thread.cancel` frames must reach the process-local runtime owner while the thread is active. The current deployment contract is single-owner/sticky; a replica that has only the durable row and no local registry state rejects live frames as unavailable rather than treating the thread as terminal.

## Why it is fragile

An acknowledgement, tool event, runtime mailbox, and terminal state can race. Sending an event before its causal acknowledgement, accepting input after terminal state, or completing a waiter before the tool-result acknowledgement makes the wire trace contradictory even with one pod.

Backend-owned client adapters do not pass bundled resources through `SkillApprovalGate` because exact skill lookup and invocation resolve enabled compiled adapters before bundle fallback. Keep the bundle list backend-owned and reviewed; do not let user-scoped file-backed Skills declare the client WebSocket transport.

## Safe-change guidance

- Keep strict JSON decoding and reject unknown fields.
- Validate and serialize initial input before registering live thread state. Propagate startup cancellation instead of converting it to a rejected acknowledgement.
- Serialize the shared `message.submit` and `thread.cancel` receipt check and acceptance in the single backend process.
- Serialize input acceptance with terminal persistence. Reserve the Skills mailbox, commit the message and revision, then publish the input; release the reservation without publishing when the commit fails. Release the event gate immediately after the acknowledgement is sent and before status feedback.
- Persist a tool result before acknowledging it; complete the suspended Skill only after sending the acknowledgement.
- Send live `thread.status` feedback after accepted submit/cancel acknowledgements without adding it to durable replay.
- Persist pending client tool calls as cancelled before propagating thread cancellation.
- Refresh public thread runtime leases while the process owns the live runtime. Recovery must only fail expired leases or already failed recovered threads missing their terminal event.
- Keep active-thread WebSocket routing sticky to the runtime owner. Do not report a local registry miss as terminal while the durable execution is still running.
- Use the latest accepted device for a new client tool call. Capabilities remain metadata and do not gate client operations.
- Keep client Skills in their relevant request-scoped skill-resolution catalog categories rather than adding them to the direct agent catalog or Skills graph core-tool list. Define client transport metadata and operation payloads in bundled `SKILL.md`, not Kotlin tool definitions.
- Do not allow arbitrary user-installed bundles to declare the client WebSocket transport.
- Keep replay subscription-before-query, re-query durable events from the last covered sequence before consuming bounded live signals, and suppress duplicate delivery by sequence.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.http.BackendPublicClientContractRouteTest'` and `./gradlew :agent:test`. Cover create idempotency, strict frames, second input during an active run, acknowledgement ordering, tool result duplicates/conflicts, cancellation, and reconnect replay.
