# Execution lifecycle

## Invariant

`AgentFacade` is the long-lived, stateful entry point for one conversation and one active execution. Starting a turn or changing its agent/context cancels the previous graph job. `GraphSessionService` is thread-safe for callbacks but records only one task at a time.

`submitToActiveRun` is an explicit continuation path for an open agent execution. It does not start a second facade task or alter the existing new-turn cancellation behavior. Agents advertise this capability through `supportsActiveRunInput`; submission returns `false` when the selected agent does not support it, no run is open, or the run has sealed before finalization.

The mailbox-backed continuation implementation gives each Skills execution `Open(queue, streamRevision, reservations)` and `Closed` states guarded by one coroutine mutex. Submission, reserved publication, FIFO draining, final sealing, and closing use that mutex, so accepted input has one ordering point relative to closure. A reservation keeps final sealing open while a host commits durable state, then either publishes the input or releases the reservation without notification. `SteerableChat` separately owns the active LLM child: it selects between child completion and the mailbox notification, cancels only that child when input wins, and lets parent or provider cancellation propagate. Started tools remain non-interruptible; their results precede queued user input. A provisional tool call is committed only after checking the queue, and a final response is committed only when the queue and reservations are empty. Queued input returns directly to the main LLM without repeating turn setup. Explicit cancellation closes the mailbox before cancelling the graph job.

Each accepted continuation advances an execution-scoped stream revision under the mailbox mutex. Every replacement LLM request captures that revision before its provider child starts, and every streamed text chunk carries the captured value. Consumers discard chunks from older revisions; they do not infer chunk ownership from collection time.

Server-side and other concurrent callers must create an `AgentExecutionKernel` per request or isolated execution scope instead of sharing a facade, graph agent, or session service.

## Why this is fragile

The facade owns mutable context, execution state, active-agent routing, and session start/finish. Overlapping work can attach steps to the wrong session, overwrite newer context, or clear `isExecuting` early. Completed-turn memory capture is graph-owned and uses successful graph finalization as its boundary rather than facade acceptance.

## Safe changes

- Keep `executeForResult` cancellation, generation capture, session start, and session finish as one lifecycle.
- Route mid-run input only through `submitToActiveRun`; do not reinterpret `executeForResult` as enqueueing.
- Keep continuation state execution-scoped. Serialize submission, draining, final sealing, and closing through the mailbox mutex; never hold it while calling a provider or executing a tool.
- Discard provisional LLM responses when queued input wins a tool or final boundary, and seal before memory-aware finalization.
- Advance the stream revision with accepted input and attach the captured revision where `NodesLLM` produces each chunk.
- Keep mailbox closure suspending and serialized before explicit graph cancellation. Do not reintroduce a separate job-based acceptance gate.
- Update facade context only from the current execution and restore the facade's base invocation metadata after per-call overrides.
- Preserve cancellation propagation through `AgentExecutor`, `TraceableAgent`, and `GraphExecutionDelegate`.
- Do not make `GraphSessionService` multi-task by adding more shared mutable state. Introduce an explicit execution/session object if parallel tracing becomes a requirement.
- Keep memory recall immediately after history input and before the graph-specific turn setup. Identify injected memory through its structural provenance marker, remove the previous turn's injection, and insert fresh recall before classification or core-tool installation. Keep completed-turn capture inside graph finalization: snapshot before history summarization, schedule only after finalization succeeds, and isolate capture failures from the returned turn.

## Verification

Run `./gradlew :agent:test`. Cover cancellation by a new turn or context change, invocation-metadata overrides, session finalization on failure, memory finalization failure/cancellation, and isolation between request-scoped kernels.
