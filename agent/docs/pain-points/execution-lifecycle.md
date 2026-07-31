# Execution lifecycle

## Invariant

`AgentFacade` is the long-lived, stateful entry point for one conversation and one active execution. Starting a turn or changing its agent/context cancels the previous graph job. `GraphSessionService` is thread-safe for callbacks but records only one task at a time.

`submitToActiveRun` is an explicit continuation path for an open `SkillsGraphBasedAgent` execution. It does not start a second facade task or alter the existing new-turn cancellation behavior. It returns `false` for classic graph executions, missing runs, and Skills runs sealed before finalization.

Server-side and other concurrent callers must create an `AgentExecutionKernel` per request or isolated execution scope instead of sharing a facade, graph agent, or session service.

## Why this is fragile

The facade owns mutable context, execution state, active-agent routing, and session start/finish. Overlapping work can attach steps to the wrong session, overwrite newer context, or clear `isExecuting` early. Completed-turn memory capture is graph-owned and uses successful graph finalization as its boundary rather than facade acceptance.

## Safe changes

- Keep `executeForResult` cancellation, generation capture, session start, and session finish as one lifecycle.
- Route mid-run input only through `submitToActiveRun`; do not reinterpret `executeForResult` as enqueueing.
- Update facade context only from the current execution and restore the facade's base invocation metadata after per-call overrides.
- Preserve cancellation propagation through `AgentExecutor`, `TraceableAgent`, and `GraphExecutionDelegate`.
- Do not make `GraphSessionService` multi-task by adding more shared mutable state. Introduce an explicit execution/session object if parallel tracing becomes a requirement.
- Keep memory recall immediately after history input and before the graph-specific turn setup. Identify injected memory through its structural provenance marker, remove the previous turn's injection, and insert fresh recall before classification or core-tool installation. Keep completed-turn capture inside graph finalization: snapshot before history summarization, schedule only after finalization succeeds, and isolate capture failures from the returned turn.

## Verification

Run `./gradlew :agent:test`. Cover cancellation by a new turn or context change, invocation-metadata overrides, session finalization on failure, memory finalization failure/cancellation, and isolation between request-scoped kernels.
