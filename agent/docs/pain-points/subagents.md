# Subagent execution

## Invariant

The parent receives `SubagentTool` as an ordinary `LLMToolSetup` and awaits each `SpawnSubagent` invocation in its current coroutine. The tool constructs one isolated child context and creates a fresh `Agent` through a host-supplied factory. The default `ToolLoopGraphBasedAgent` is a reusable model/tool loop with ordinary agent lifecycle and streaming. Explicitly selected tools replace both advertised schemas and executable lookup. Children have no spawn capability or parent turn-setup nodes. Parent cancellation propagates through the child; queued parent input follows the completed tool result.

The host supplies the same LLM API and complete invocation metadata. The tool leaves child `sideEffects` uncollected and sets `AgentRuntimeEventSink.NONE`; required interactions emitted by host tools retain their existing owners. The parent records the enclosing spawn tool call and its result.

## Why this is fragile

Reusing a parent agent cancels its active job. Reusing its LLM nodes or tool executor leaks child output through observable flows even when the child's event sink is empty. Altering backend invocation identifiers breaks client tools. Graph retries around a child could replay completed side effects.

## Safe changes

- Bind spawning to the initial parent execution settings before graph setup can restrict or classify tools. `SubagentToolFactory` in `:sharedLogic` resolves settings, selected tools, and instructions into `SubagentTool.Setup`. The tool in `:agent` owns context construction, validation, and structured results. Hosts supply a `(maxTurns: Int) -> Agent` factory; `ToolLoopGraphBasedAgent` owns graph construction and turn counting and reuses the shared execution delegate for tracing and cancellation.
- Factories return a fresh agent that respects the supplied context, capabilities, and turn budget. Budget exhaustion throws `AgentTurnLimitException`, which the tool maps to `subagent_turn_limit`. Do not reuse a parent or singleton agent: starting an execution cancels its previous job.
- Keep child model, tool tables, turn counter, and graph lifecycle private to each invocation. Share plain node helpers rather than adding parent setup flags.
- Keep provider retries in the host API. Neither child graphs nor parent tool-call batches are graph-retried: a later failing tool must not replay an earlier child's side effects. Child failures become structured spawn results; cancellation remains exceptional. Check the model-turn limit before each LLM request and accept final output on the last allowed turn.
- Reuse `ToolInvokeSkill` with a bundle loader restricted to the owner and bundles selected and approved at spawn. Do not repeat approval inside the child or change the shared executor's stored/loose directory behavior. Never pass an unrestricted catalog or bundle loader into the child command helper.
- Keep durable backend child records and recovery separate from the shared execution model.

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover waiting/resumption, queued input, cancellation, exact tool lookup, bundle restrictions, model selection, turn limits, stream isolation, backend client identity, and usage accounting.

See [usage and Skill instructions](../../../docs/subagents.md).
