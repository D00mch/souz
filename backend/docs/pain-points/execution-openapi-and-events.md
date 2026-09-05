# Execution, OpenAPI, and events

## Invariant

`AgentExecutionService` owns product execution lifecycle, cancellation, and option continuation. For the Client-Souz contract, an execution is a thread and its `id` is the public `threadId`. Product messages are stored separately from runtime continuation state, and `conversationId = chatId.toString()` is the stable agent-session identity. Each turn uses the backend's single request-scoped steerable skills graph. Request-scoped runtimes rebuild from persisted session state, while storage enforces one active execution per chat.

Provider HTTP clients and OAuth transports are process-owned resources. A request-scoped LLM API retains only execution settings, lazily resolved credentials, lightweight provider adapters, retry state, and cumulative usage. Backend execution never constructs or routes to Giga; capability discovery and request validation apply the same backend provider policy before an execution is persisted or resumed.

Background execution is launched through a registered lifecycle job whose execution body is held behind an internal start gate until registration is visible. Cancellation finalization and lease cleanup run before the job unregisters or completes. Process shutdown stops HTTP intake, cancels and joins application work, then closes provider clients, the local runtime, and the datasource in order.

Each initial execution snapshots its effective compiled-tool names into execution metadata, and option continuations reuse that snapshot. One immutable request-scoped catalog applies the snapshot to compiled and execution-bound LLM tools, then merges built-in client operations only for Client-Souz executions. The skills graph uses that final catalog for inventory, lookup, and generic invocation while exposing only its fixed core tools to the model.

The proxy-facing event API retains its internal durable events and live-only `message.delta`. The Client-Souz socket filters that stream to `tool.call.started`, exactly one terminal thread event, and out-of-band `message.created` pushes (`executionId == null`) delivered into a chat from another of the user's channels — ordinary in-thread `message.created` stays filtered out. Public sequence values come from the shared chat-local `agent_events` sequence and can contain gaps caused by internal events.

## Why it is fragile

Execution state crosses HTTP responses, WebSocket delivery, repository transactions, cancellation, and resumed options. Persisting live deltas or conflating product messages with continuation state can duplicate replay, corrupt session recovery, or make clients observe contradictory execution states.

Generated OpenAPI is also easy to drift: route helpers and deferred registration cannot be described safely by unrestricted compiler inference.

## Safe-change guidance

- Keep execution launch/finalization in `AgentExecutionService` and session reconstruction in the runtime factory/repository layer.
- Advance `basedOnMessageSeq` only across context that the runtime has observed. An execute barrier loads the bounded durable gap through its trigger and filters ordinary rows already represented in the saved session. Client history advances the cursor only with the `message.submit` that claims it. If history precedes a terminal assistant row, the next execute inserts that history after the saved response rather than retroactively changing the completed turn.
- Keep provider clients out of request-scoped runtimes and close process-owned transports exactly once at backend shutdown.
- Route nested search, research, vision, and summarization calls through the current execution API so credentials, timeout, and usage stay in the same scope.
- Reject unsupported backend providers explicitly. Do not silently replace a persisted or requested Giga model with another provider.
- Do not read the shared JVM agent preference or mutate singleton tool policy. Build the immutable execution catalog from execution metadata and keep compiled-tool selection request-scoped.
- Publish internal deltas only on the live bus. Client tool starts and thread terminals are durable `agent_events`; acknowledgements are not events.
- Register a Client-Souz execution before launching its steerable runtime. Accepted mid-run input must reserve the runtime's active controller before durable commit, and public events must wait until accepted acknowledgements are sent.
- Register background work before its body can run. Keep cancellation persistence and event emission non-cancellable, and unregister only in the lifecycle job's outermost cleanup.
- Keep complete client tool arguments, results or errors, deadline, and result idempotency state in `tool_calls`. Only one client tool waiter may be outstanding per thread.
- Preserve the canonical-or-legacy replay union and keep compatibility payloads structurally distinct.
- Give every ordinary HTTP route a stable operation ID, tag, inputs, success responses, structured errors, and trusted-proxy security where applicable.
- Keep compiler inference limited to explicitly commented paths; runtime route descriptions and reflection are authoritative for helpers and conditional behavior.
- Exclude WebSocket routes and their upgrade fallback through both compiler and runtime OpenAPI controls.

## Verification

Run `./gradlew :backend:test`. Cover sync and async lifecycle, one-active-thread conflicts, mid-run input, cancellation and option resume, client tool result idempotency and timeout, durable public replay, internal live deltas, route metadata, and WebSocket exclusion.
