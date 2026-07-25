# Execution lifecycle

## Invariant

`AgentFacade` is the long-lived, stateful entry point for one conversation and one active execution. Starting a turn or changing its agent/context cancels the previous graph job. `GraphSessionService` is thread-safe for callbacks but records only one task at a time.

Server-side and other concurrent callers must create an `AgentExecutionKernel` per request or isolated execution scope instead of sharing a facade, graph agent, or session service.

## Why this is fragile

The facade owns mutable context, execution state, active-agent routing, session start/finish, and a generation guard for post-turn memory capture. Overlapping work can attach steps to the wrong session, overwrite newer context, clear `isExecuting` early, or capture a cancelled turn.

## Safe changes

- Keep `executeForResult` cancellation, generation capture, session start, and session finish as one lifecycle.
- Update facade context only from the current execution and restore the facade's base invocation metadata after per-call overrides.
- Preserve cancellation propagation through `AgentExecutor`, `TraceableAgent`, and `GraphExecutionDelegate`.
- Do not make `GraphSessionService` multi-task by adding more shared mutable state. Introduce an explicit execution/session object if parallel tracing becomes a requirement.
- Keep completed-turn memory capture after successful current-generation execution; it is asynchronous and must not make the turn fail.

## Verification

Run `./gradlew :agent:test`. Cover cancellation by a new turn or context change, stale-generation completion, invocation-metadata overrides, session finalization on failure, and isolation between request-scoped kernels.
