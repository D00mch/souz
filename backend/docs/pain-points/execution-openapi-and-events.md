# Execution, OpenAPI, and events

## Invariant

`AgentExecutionService` owns product execution lifecycle, cancellation, and option continuation. For the Client-Souz contract, an execution is a thread and its `id` is the public `threadId`. Product messages are stored separately from runtime continuation state, and `conversationId = chatId.toString()` is the stable agent-session identity. Each turn uses the backend's single request-scoped steerable skills graph. Request-scoped runtimes rebuild from persisted session state, while storage enforces one active execution per chat.

Provider HTTP clients and OAuth transports are process-owned resources. A request-scoped LLM API retains only execution settings, lazily resolved credentials, lightweight provider adapters, retry state, and cumulative usage. Backend execution never constructs or routes to Giga; capability discovery and request validation apply the same backend provider policy before an execution is persisted or resumed.

Successful finalization is non-cancellable: one transaction commits the assistant message, continuation, chat timestamp, and completed execution, then logs cumulative token usage and appends completion events. One INFO record has `event = execution.token_usage` and `input_tokens`, `output_tokens`, `total_tokens`, `cached_input_tokens` in JSON `kvpList`; `mdc` supplies `userId`, `chatId`, and `threadId`. Counters include nested calls, option continuations, and zero values. Failed/cancelled executions, waiting options, duplicate requests, and event replay do not log usage. Logging is synchronous after the completed row is stored; it is not transactional with PostgreSQL and cannot guarantee accounting across process crashes.

Sum completed-execution tokens from exported JSON Lines (Logback encodes counter values as strings):

```sh
jq -s '[.[] | (.kvpList // [] | add) | select(.event == "execution.token_usage") | .total_tokens | tonumber] | add // 0' backend.jsonl
```

Background execution is launched through a registered lifecycle job whose execution body is held behind an internal start gate until registration is visible. Cancellation finalization and lease cleanup run before the job unregisters or completes. Process shutdown stops HTTP intake, cancels and joins application work, then closes provider clients, the local runtime, and the datasource in order.

The launcher invokes cancellation cleanup, including cancellation before startup. `waiting_option` marks a fully saved option event and continuation; its continuation and execution transition share a transaction. Incomplete handoffs remain running and are cancelled on shutdown. Option answers join the handoff before resuming. Explicit cancellation still terminates a saved wait. Turn commits lock the chat and execution, and check running status, cancellation intent, and runtime owner before any message or continuation write. Lifecycle updates write only their fields and return the stored row, preserving concurrent lease, device-context, and message-link changes. Cleanup preserves existing terminal outcomes and repairs their matching event without logging usage. PostgreSQL deduplicates both HTTP execution and public thread terminal events across workers.

Each initial execution snapshots its effective compiled-tool names, `narrateSteps` preference (default `false`), and optional reasoning effort into execution metadata, and option continuations reuse that snapshot. An absent effort remains unset, including for executions created before the setting was available. One immutable request-scoped catalog applies the snapshot to compiled and execution-bound LLM tools, then merges built-in client operations for local or cross-channel WebSocket calls. The skills graph uses that final catalog for inventory, lookup, and generic invocation while exposing only its fixed core tools to the model.

The proxy-facing event API retains its internal durable events and live-only `message.delta`. The Client-Souz socket filters that stream to live-only `assistant.message` progress, `tool.call.started`, exactly one terminal thread event, and out-of-band `message.created` pushes (`executionId == null`) delivered into a chat from another of the user's channels — ordinary in-thread `message.created` stays filtered out. Public sequence values come from the shared chat-local `agent_events` sequence and can contain gaps caused by internal events.

## Why it is fragile

Execution state crosses HTTP responses, WebSocket delivery, repository transactions, cancellation, and resumed options. Persisting live deltas or conflating product messages with continuation state can duplicate replay, corrupt session recovery, or make clients observe contradictory execution states.

Generated OpenAPI is also easy to drift: route helpers and deferred registration cannot be described safely by unrestricted compiler inference.

## Safe-change guidance

- Keep execution launch/finalization and event-sink creation in `AgentExecutionService`, and session reconstruction in the runtime factory/repository layer. Interrupted-execution finalization preserves waiting options and terminal outcomes and repairs missing terminal events; public startup failures persist their terminal event in the application-owned acceptance operation, independently of socket delivery.
- Advance `basedOnMessageSeq` only across context that the runtime has observed. An execute barrier loads the bounded durable gap through its trigger and filters ordinary rows already represented in the saved session. Client history advances the cursor only with the `message.submit` that claims it. If history precedes a terminal assistant row, the next execute inserts that history after the saved response rather than retroactively changing the completed turn.
- Keep provider clients out of request-scoped runtimes and close process-owned transports exactly once at backend shutdown.
- Route nested search, research, vision, and summarization calls through the current execution API so credentials, timeout, and usage stay in the same scope.
- `SpawnSubagent` uses that same execution API and the filtered request catalog. Preserve the parent's complete tool metadata, especially the public thread and chat identifiers needed by client tools. Only the parent persists conversation output; child graph events are isolated while client tool interactions retain their normal routing.
- Configured subagent models may use another supported provider. Keep their explicit provider and raw ID through the execution API, using that provider's execution-scoped credentials and the shared retry/usage wrappers. Requests without routing metadata resolve enum selectors through the shared resolver and `executionModelId`; unknown or unsupported selectors remain errors. Supply the dedicated summarization model in its request; adapters do not override model IDs.
- Reject unsupported backend providers explicitly. Do not silently replace a persisted or requested Giga model with another provider.
- Do not read the shared JVM agent preference or mutate singleton tool policy. Build the immutable execution catalog from execution metadata and keep compiled-tool selection request-scoped.
- Publish internal deltas only on the live bus. Same-thread client tool starts and thread terminals are durable `agent_events`; cross-channel tool starts are live-only. Acknowledgements are not events.
- `narrateSteps` gates the additive RU/EN prompt instruction and live `assistant.message` publication independently of streaming and tool events. Preserve custom prompts, the client memory notice, and separate assistant blocks.
- Bot observers subscribe before execution and consume only that execution's progress through the bounded live queue, without replay or client commands. They must not advertise a connected device. A child coroutine sends sequentially with existing formatting and lease checks; ordinary failures are best-effort and cancellation propagates. Cancel and join the sender and close its subscription before the final reply, including on poll cancellation or lease loss. Pending progress may be dropped; accepted external sends cannot be recalled.
- Register a Client-Souz execution before launching its steerable runtime. Accepted mid-run input must reserve the runtime's active controller before durable commit, and the submitting socket must serialize command handling through acknowledgement/status delivery before sending subsequent events. Runtime event persistence never waits for acknowledgements.
- Register background work before its body can run. Keep cancellation persistence and event emission non-cancellable, and unregister only in the lifecycle job's outermost cleanup.
- Keep complete same-thread client tool arguments, results or errors, deadline, and result idempotency state in `tool_calls`. Only one client tool waiter may be outstanding per thread.
- Runtime tool audit previews are sanitized once, then reused for persistence and optional events. Sanitization creates independent containers without mutating the input; keep persistence outside the event-enabled guard.
- Preserve the canonical-or-legacy replay union and keep compatibility payloads structurally distinct.
- Give every ordinary HTTP route a stable operation ID, tag, inputs, success responses, structured errors, and trusted-proxy security where applicable.
- Keep compiler inference limited to explicitly commented paths; runtime route descriptions and reflection are authoritative for helpers and conditional behavior.
- Exclude WebSocket routes and their upgrade fallback through both compiler and runtime OpenAPI controls.

## Verification

Run `./gradlew :backend:test`. Cover sync and async lifecycle, one-active-thread conflicts, mid-run input, cancellation and option resume, client tool result idempotency and timeout, durable public replay, internal live deltas, route metadata, and WebSocket exclusion.
