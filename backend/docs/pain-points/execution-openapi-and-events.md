# Execution, OpenAPI, and events

## Invariant

`AgentExecutionService` owns product execution lifecycle, cancellation, and option continuation. Product messages are stored separately from runtime continuation state, and `conversationId = chatId.toString()` is the stable agent-session identity. Request-scoped runtimes rebuild from persisted session state, while storage enforces one active execution per chat.

New conversations use the configured backend agent; persisted conversations retain their stored agent. Each initial execution snapshots its effective compiled-tool names into execution metadata, and option continuations reuse that snapshot. Request-scoped immutable filters apply the snapshot to classic graph classification and to the skills graph's `GetSkills` and generic `RunSkillCommand`; core tools and user-installed file-backed skills remain available.

`message.delta` is live-only for current executions. Execution lifecycle, completed message, tool-call, option, failure, and cancellation events are durable and replayable. Compatibility replay may expose older partial or durable-delta rows, but those rows must remain a disjoint fallback rather than weakening the canonical typed durable-event variants.

## Why it is fragile

Execution state crosses HTTP responses, WebSocket delivery, repository transactions, cancellation, and resumed options. Persisting live deltas or conflating product messages with continuation state can duplicate replay, corrupt session recovery, or make clients observe contradictory execution states.

Generated OpenAPI is also easy to drift: route helpers and deferred registration cannot be described safely by unrestricted compiler inference.

## Safe-change guidance

- Keep execution launch/finalization in `AgentExecutionService` and session reconstruction in the runtime factory/repository layer.
- Do not read the shared JVM agent preference or mutate singleton tool policy. Build agent selection and compiled-tool filtering from backend configuration, persisted session state, and execution metadata.
- Publish new deltas only on the live bus; persist only canonical durable events unless a migration explicitly changes the contract.
- Preserve the canonical-or-legacy replay union and keep compatibility payloads structurally distinct.
- Give every ordinary HTTP route a stable operation ID, tag, inputs, success responses, structured errors, and trusted-proxy security where applicable.
- Keep compiler inference limited to explicitly commented paths; runtime route descriptions and reflection are authoritative for helpers and conditional behavior.
- Exclude WebSocket routes and their upgrade fallback through both compiler and runtime OpenAPI controls.

## Verification

Run `./gradlew :backend:test`. Cover sync and async lifecycle, one-active-execution conflicts, cancellation and option resume, durable replay versus live deltas, legacy compatibility rows, typed event schemas, route metadata, and WebSocket exclusion.
