# Execution, OpenAPI, and events

## Invariant

`AgentExecutionService` owns product execution lifecycle, cancellation, and option continuation. For the Client-Souz contract, an execution is a thread and its `id` is the public `threadId`. Product messages are stored separately from runtime continuation state, and `conversationId = chatId.toString()` is the stable agent-session identity. Each turn uses the backend's single request-scoped steerable direct-tool graph. Request-scoped runtimes rebuild from persisted session state, while storage enforces one active execution per chat.

Each initial execution snapshots its effective compiled-tool names into execution metadata, and option continuations reuse that snapshot. Request-scoped immutable filters apply the snapshot to direct-tool category selection and invocation. Built-in client operations are ordinary typed tools merged only into Client-Souz executions.

The proxy-facing event API retains its internal durable events and live-only `message.delta`. The Client-Souz socket filters that stream to `tool.call.started` and exactly one terminal thread event. Public sequence values come from the shared chat-local `agent_events` sequence and can contain gaps caused by internal events.

## Why it is fragile

Execution state crosses HTTP responses, WebSocket delivery, repository transactions, cancellation, and resumed options. Persisting live deltas or conflating product messages with continuation state can duplicate replay, corrupt session recovery, or make clients observe contradictory execution states.

Generated OpenAPI is also easy to drift: route helpers and deferred registration cannot be described safely by unrestricted compiler inference.

## Safe-change guidance

- Keep execution launch/finalization in `AgentExecutionService` and session reconstruction in the runtime factory/repository layer.
- Do not read the shared JVM agent preference or mutate singleton tool policy. Build compiled-tool filtering from execution metadata and keep it request-scoped.
- Publish internal deltas only on the live bus. Client tool starts and thread terminals are durable `agent_events`; acknowledgements are not events.
- Register a Client-Souz execution before launching its steerable runtime. Accepted mid-run input must use `submitToActiveRun`, and public events must wait until accepted acknowledgements are sent.
- Keep complete client tool arguments, results or errors, deadline, and result idempotency state in `tool_calls`. Only one client tool waiter may be outstanding per thread.
- Preserve the canonical-or-legacy replay union and keep compatibility payloads structurally distinct.
- Give every ordinary HTTP route a stable operation ID, tag, inputs, success responses, structured errors, and trusted-proxy security where applicable.
- Keep compiler inference limited to explicitly commented paths; runtime route descriptions and reflection are authoritative for helpers and conditional behavior.
- Exclude WebSocket routes and their upgrade fallback through both compiler and runtime OpenAPI controls.

## Verification

Run `./gradlew :backend:test`. Cover sync and async lifecycle, one-active-thread conflicts, mid-run input, cancellation and option resume, client tool result idempotency and timeout, durable public replay, internal live deltas, route metadata, and WebSocket exclusion.
