# Subagent execution

## Invariant

The parent receives `SpawnSubagent`, `WaitSubagent`, and `CancelSubagent` through `AgentCoreTools`. Spawn validates and prepares selected capabilities, starts an isolated child, and returns a UUID without awaiting its answer. The parent can continue its model/tool loop and manage multiple children independently. Children have no lifecycle tools, recursive spawning, or parent turn-setup nodes.

`GraphExecutionDelegate` wraps each graph run in `withSubagentTasks`, using the host's `AgentSettingsProvider.subagentTaskTimeout`. A coroutine-context registry keeps cancellable jobs and their final results for that execution only. Rebinding tools after model routing preserves access to the same registry. Graph-local scopes and queued steering do not own the children. Normal completion, failure, and parent cancellation cancel all unfinished children, clear registry entries, and join child cleanup before returning. Direct tool hosts must wrap their parent loop in `withSubagentTasks(taskTimeout = ...)` (ten minutes by default); unscoped calls return `subagent_unavailable`.

Status is `running`, `cancelling`, `completed`, `failed`, or `cancelled`. Reads are non-consuming and terminal results remain stable until parent exit. Wait accepts `timeoutMs` from 0 to 30,000 (default 30,000); zero polls without waiting, and timeout returns the current status without cancelling the child. Use the full wait when the parent has no other work to avoid repeated model calls. Cancellation is idempotent: `cancelling` means cancellation was requested, and `cancelled` means child cleanup has finished. Cancelling a completed child preserves its result. Unknown or expired IDs return `subagent_not_found`. Parent cancellation remains exceptional.

Each execution admits at most eight active children, including cancellation cleanup, and 32 children in total. Rejected spawns return `subagent_concurrency_limit` or `subagent_task_limit` without starting work. Each child has a positive, finite cooperative deadline, defaulting to ten minutes; expiry retains a `failed` result with `subagent_timeout` after cleanup. Blocking or cancellation-ignoring tools can delay termination. Each retained outcome is limited to 64 KiB of UTF-8 JSON, bounding stored outcome JSON to 2 MiB per execution. An oversized success or failure is replaced with `failed`, `subagent_result_limit`, its original `resultBytes`, and a side-effect warning; work is not retried. Client calls use the backend's shared live dispatcher and independent correlation IDs, including calls in the same thread.

The host supplies the same LLM API and complete invocation metadata. Each child has a fresh agent, history, tool lookup, and output stream. The tool leaves child `sideEffects` uncollected and sets `AgentRuntimeEventSink.NONE`; host tools retain their permission and client-interaction owners. The parent records its lifecycle tool calls and results, including normal Knowledge offloading for large results. Cancellation does not undo tool side effects; callers must inspect existing work before retrying.

Backend provider calls wait cancellably for shared per-user and provider concurrency capacity. A parent queues alongside children rather than failing when they occupy every slot. FIFO admission prevents later child calls from overtaking queued parent calls; capacity stays occupied through provider cancellation cleanup. Request/token quotas and hook call budgets are checked after admission, before the provider attempt.

Completed child output carries its selected files as structured `files`, also included in the result JSON for subsequent tool arguments. It does not set the parent's `replyFiles`: the parent explicitly selects any file it wants to include in its reply. Child and parent file tools share the invocation's ownership.

## Why this is fragile

Reusing a parent agent cancels its active job. Reusing its LLM nodes or tool executor leaks child output through observable flows even when the child's event sink is empty. Altering backend invocation identifiers breaks client tools. Graph retries around a child could replay completed side effects.

## Safe changes

- Bind spawning to the initial parent execution settings before graph setup can restrict or classify tools. `SubagentToolFactory` in `:sharedLogic` puts only selected capabilities in `SubagentTool.Setup.settings.tools`, using the list-based `AgentTools` factory to reject duplicate names and preserve selected categories. The tool in `:agent` derives advertised schemas from that executable lookup and owns context construction, input validation, and structured results. Hosts supply a `(maxTurns: Int) -> Agent` factory; `ToolLoopGraphBasedAgent` owns graph construction and turn counting and reuses the shared execution delegate for tracing and cancellation.
- Factories return a fresh agent that respects the supplied context, capabilities, and turn budget. Budget exhaustion throws `AgentTurnLimitException` with this execution's returned tool messages. Result retrieval reports `failed` with `subagent_turn_limit`, a side-effect warning, and bounded recent tool results with truncation and omission indicators. Returned tool messages can contain errors and do not imply task success; exhaustion does not roll back effects or make repeating the task safe. Do not reuse a parent or singleton agent: starting an execution cancels its previous job.
- Keep child model, tool tables, turn counter, and graph lifecycle private to each invocation. Share plain node helpers rather than adding parent setup flags.
- Forward the child's optional reasoning effort through `AgentSettings` to both streaming and ordinary LLM requests. Omission leaves it unspecified for provider defaults; do not inherit the parent's effort or retain a previous child's override.
- Snapshot the parent's raw model ID and explicit provider in `AgentSettings`. The spawn factory merges host-configured model routes with the parent route taking precedence, and uses that same map for advertised choices and validation. Omission always inherits; never infer a configured ID's provider through the model enum or mutable parent settings.
- Child graphs disable graph retries and rely on the host API for provider retries. Parent graphs retry only explicitly opted-in provider nodes; tool-call batches and enclosing coordination nodes are not retried. A later failing tool must not replay an earlier child's side effects. Child failures become stored structured results; child-only cancellation becomes `cancelled`, while parent cancellation propagates. Check the model-turn limit before each LLM request and accept final output on the last allowed turn.
- Reuse `ToolInvokeSkill` with a bundle loader restricted to the owner and bundles selected and approved at spawn. Do not repeat approval inside the child or change the shared executor's stored/loose directory behavior. Never pass an unrestricted catalog or bundle loader into the child command helper.
- Keep durable backend child records and recovery separate from the shared execution model.

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover concurrent children, bounded waiting, selective cancellation, completion races, parent cleanup, queued input, exact tool lookup, bundle restrictions, model selection, turn limits, stream isolation, backend client identity, and usage accounting.

See [usage and Skill instructions](../../docs/subagents.md).
