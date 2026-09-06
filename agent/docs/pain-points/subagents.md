# Subagent execution

## Invariant

The parent awaits each `SpawnSubagent` call in its current coroutine. `SubagentRunner` constructs the child context once and directly runs fresh graph nodes and a tool executor, without an `Agent` lifecycle or active-job tracking. Its explicitly selected tools replace both advertised schemas and executable lookup. Children have no spawn capability or parent turn-setup nodes. Parent cancellation propagates through the child; queued parent input follows the completed tool result.

The host supplies the same LLM API and complete invocation metadata. Child text and graph tool events use an isolated stream and `AgentRuntimeEventSink.NONE`; required interactions emitted by host tools retain their existing owners. The parent records the enclosing spawn tool call and its result.

## Why this is fragile

Reusing a parent agent cancels its active job. Reusing its LLM nodes or tool executor leaks child output through observable flows even when the child's event sink is empty. Altering backend invocation identifiers breaks client tools. Graph retries around a child could replay completed side effects.

## Safe changes

- Bind spawning to the initial parent execution settings before graph setup can restrict or classify tools.
- Keep child model, tool tables, turn counter, and graph lifecycle private to each invocation. Share plain node helpers rather than adding parent setup flags.
- Keep provider retries in the host API. Neither child graphs nor parent tool-call batches are graph-retried: a later failing tool must not replay an earlier child's side effects. Child failures become structured spawn results; cancellation remains exceptional. Check the model-turn limit before each LLM request and accept final output on the last allowed turn.
- Reuse `ToolInvokeSkill` with a provider restricted to the bundles selected and approved at spawn. Do not repeat approval inside the child or change the shared executor's stored/loose directory behavior. Never pass an unrestricted catalog or registry into the child command helper.
- Keep durable backend child records and recovery separate from the shared execution model.

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test :desktopApp:test`. Cover waiting/resumption, queued input, cancellation, exact tool lookup, bundle restrictions, model selection, turn limits, stream isolation, backend client identity, and usage accounting.

See [usage and Skill instructions](../../../docs/subagents.md).
