# Subagent execution

## Invariant

The parent receives `SubagentTool` as an ordinary `LLMToolSetup` and awaits each `SpawnSubagent` invocation in its current coroutine. The tool constructs one isolated child context and creates a fresh `Agent` through a host-supplied factory. The default `ToolLoopGraphBasedAgent` is a reusable model/tool loop with ordinary agent lifecycle and streaming. Explicitly selected tools replace both advertised schemas and executable lookup. Children have no spawn capability or parent turn-setup nodes. Parent cancellation propagates through the child; queued parent input follows the completed tool result.

The host supplies the same LLM API and complete invocation metadata. The tool leaves child `sideEffects` uncollected and sets `AgentRuntimeEventSink.NONE`; required interactions emitted by host tools retain their existing owners. The parent records the enclosing spawn tool call and its result.

## Why this is fragile

Reusing a parent agent cancels its active job. Reusing its LLM nodes or tool executor leaks child output through observable flows even when the child's event sink is empty. Altering backend invocation identifiers breaks client tools. Graph retries around a child could replay completed side effects.

## Safe changes

- Bind spawning to the initial parent execution settings before graph setup can restrict or classify tools. `SubagentToolFactory` in `:sharedLogic` puts only selected capabilities in `SubagentTool.Setup.settings.tools`, using the list-based `AgentTools` factory to reject duplicate names and preserve selected categories. The tool in `:agent` derives advertised schemas from that executable lookup and owns context construction, input validation, and structured results. Hosts supply a `(maxTurns: Int) -> Agent` factory; `ToolLoopGraphBasedAgent` owns graph construction and turn counting and reuses the shared execution delegate for tracing and cancellation.
- Factories return a fresh agent that respects the supplied context, capabilities, and turn budget. Budget exhaustion throws `AgentTurnLimitException` with this execution's returned tool messages. The tool maps it to `subagent_turn_limit` with incomplete status, a side-effect warning, and bounded recent tool results with truncation and omission indicators. Returned tool messages can contain errors and do not imply task success; exhaustion does not roll back effects or make repeating the task safe. Do not reuse a parent or singleton agent: starting an execution cancels its previous job.
- Keep child model, tool tables, turn counter, and graph lifecycle private to each invocation. Share plain node helpers rather than adding parent setup flags.
- Snapshot the parent's raw model ID and explicit provider in `AgentSettings`. The spawn factory merges host-configured model routes with the parent route taking precedence, and uses that same map for advertised choices and validation. Omission always inherits; never infer a configured ID's provider through the model enum or mutable parent settings.
- Child graphs disable graph retries and rely on the host API for provider retries. Parent graphs retry only explicitly opted-in provider nodes; tool-call batches and enclosing coordination nodes are not retried. A later failing tool must not replay an earlier child's side effects. Child failures become structured spawn results; cancellation remains exceptional. Check the model-turn limit before each LLM request and accept final output on the last allowed turn.
- Reuse `ToolInvokeSkill` with a bundle loader restricted to the owner and bundles selected and approved at spawn. Do not repeat approval inside the child or change the shared executor's stored/loose directory behavior. Never pass an unrestricted catalog or bundle loader into the child command helper.
- Keep durable backend child records and recovery separate from the shared execution model.

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover waiting/resumption, queued input, cancellation, exact tool lookup, bundle restrictions, model selection, turn limits, stream isolation, backend client identity, and usage accounting.

See [usage and Skill instructions](../../../docs/subagents.md).
