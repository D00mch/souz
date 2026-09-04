# Execution lifecycle

## Invariant

`AgentFacade` is the long-lived, stateful entry point for one conversation and one active execution. Starting a turn or changing its agent/context cancels the previous graph job. `GraphSessionService` is thread-safe for callbacks but records only one task at a time.

`submitToActiveRun` is an explicit continuation path for an open agent execution. It does not start a second facade task or alter the existing new-turn cancellation behavior. The steerable agent owns its execution-scoped `ActiveRunInputController`; direct UI input remains a string, while a host can reserve the controller and publish a role-preserving history-plus-execute batch after durable commit. Submission returns `false` when no run is open or the run has sealed before finalization.

The continuation controller gives each steerable execution `Open(queue, streamRevision, reservations)` and `Closed` states guarded by one coroutine mutex. FIFO draining, final sealing, and closing use that mutex, so accepted execute input has one ordering point relative to closure. A reservation keeps final sealing open while a host commits durable state, then either publishes its structured batch or releases the reservation. Once the producer returns committed input, publication is non-cancellable and caller cancellation propagates after the controller is synchronized. `SteerableChatNode` separately owns the active LLM child: it selects between child completion and execute notification, cancels only that child when execute wins, and lets parent or provider cancellation propagate. Started tools remain non-interruptible, so queued batches follow their results. Queued work returns directly to the main LLM without repeating turn setup. Explicit agent cancellation closes the controller before cancelling the graph job.

Each accepted continuation advances an execution-scoped stream revision under the mailbox mutex. Every replacement LLM request captures that revision before its provider child starts, and every streamed text chunk carries the captured value. Consumers discard chunks from older revisions; they do not infer chunk ownership from collection time.

Server-side and other concurrent callers must create an `AgentExecutionKernel` per request or isolated execution scope instead of sharing a facade, graph agent, or session service. The request-scoped kernel exposes one steerable skills graph under `AgentId.SKILLS_GRAPH`; unsupported persisted agent IDs normalize to the first configured agent. The graph advertises and executes only its fixed core Skill tools. Those tools discover capabilities from the request-scoped catalog through the Skill inventory, and queued input keeps the same core-tool boundary for the remainder of the execution.

## Why this is fragile

The facade owns mutable context, execution state, active-agent routing, and session start/finish. Overlapping work can attach steps to the wrong session, overwrite newer context, or clear `isExecuting` early. Completed-turn memory capture is graph-owned and uses successful graph finalization as its boundary rather than facade acceptance.

## Safe changes

- Keep `executeForResult` cancellation, generation capture, session start, and session finish as one lifecycle.
- Route mid-run input through the active controller owned by the steerable agent; do not reinterpret `executeForResult` as enqueueing.
- Keep continuation state execution-scoped. Serialize submission, draining, final sealing, and closing through the controller mutex; never hold it while calling a provider or executing a tool.
- Bundle durable history with the execute input that claims it. Preserve stored roles and do not add a passive history wake-up path.
- Discard provisional LLM responses when queued input wins a tool or final boundary, and seal before memory-aware finalization.
- Advance the stream revision with accepted input and attach the captured revision where `NodesLLM` produces each chunk.
- Keep controller closure suspending and serialized before explicit graph cancellation. Do not reintroduce a separate job-based acceptance gate.
- Update facade context only from the current execution and restore the facade's base invocation metadata after per-call overrides.
- Preserve cancellation propagation through `AgentExecutor`, `Agent`, and `GraphExecutionDelegate`.
- Do not make `GraphSessionService` multi-task by adding more shared mutable state. Introduce an explicit execution/session object if parallel tracing becomes a requirement.
- Keep memory recall immediately after history input and before the graph-specific turn setup. Identify injected memory through its structural provenance marker, remove the previous turn's injection, and insert fresh recall before classification or core-tool installation. Keep completed-turn capture inside graph finalization: snapshot before history summarization, schedule only after finalization succeeds, and isolate capture failures from the returned turn.

## Verification

Run `./gradlew :agent:test`. Cover cancellation by a new turn or context change, invocation-metadata overrides, session finalization on failure, memory finalization failure/cancellation, and isolation between request-scoped kernels.
