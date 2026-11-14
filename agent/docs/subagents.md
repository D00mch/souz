# Subagent execution

## Invariant

[Core tools](../src/main/kotlin/ru/souz/agent/AgentCoreTools.kt) let the parent spawn, wait for, and cancel children independently. Spawn prepares the requested capabilities and returns an ID without waiting for the answer. The [usage guide](../../docs/subagents.md) owns tool arguments, statuses, limits, configuration, and examples.

The [task registry](../src/main/kotlin/ru/souz/agent/runtime/SubagentTasks.kt) belongs to one parent execution and survives tool rebinding and steering. Every exit cancels unfinished children, joins cleanup, and releases the registry. Scope direct tool hosts around the entire parent loop with `withSubagentTasks`. Keep waits non-consuming, terminal outcomes stable, and cancellation local to the selected child. The registry bounds active work, including cleanup, and retained outcomes. Deadlines and cancellation are cooperative.

Each [child loop](../src/main/kotlin/ru/souz/ToolLoopGraphBasedAgent.kt) uses a fresh agent, history, tool lookup, and output stream. Keep parent turn setup and lifecycle tools out of the child. Keep child streams and runtime events private while preserving the host API and complete invocation metadata for permissions, file ownership, and independently correlated client calls. Child results carry structured file references for the parent to select explicitly in its reply. Parent lifecycle results follow normal [Knowledge offloading](skills-oriented-graph.md#tool-result-offloading).

## Why this is fragile

Reusing an agent can cancel its active job. Sharing LLM nodes or a tool executor can leak child output even with events disabled. Retrying a graph step around a tool batch can repeat completed side effects.

## Safe changes

- Bind spawning to the parent settings snapshot before core-tool restriction. The [spawn factory](../../sharedLogic/src/main/kotlin/ru/souz/tool/subagent/SubagentToolFactory.kt) resolves explicit selections against the filtered catalog and [Skill approval policy](skill-activation.md#safe-changes). Keep advertised schemas aligned with the executable lookup, preserve categories and duplicate rejection, and restrict the child command helper to selected tools and owner-bound bundles approved at spawn.
- Use the same model-to-provider snapshot for advertising and validation, with the parent's exact route taking precedence and serving as the default. Apply reasoning effort only from the child's request and leave it unspecified on omission.
- Keep graph retries disabled in the child and [restrict parent retries to opted-in provider nodes](execution-lifecycle.md#safe-changes). Check the turn budget before each model request and accept final output on the last allowed turn. Preserve bounded progress and side-effect warnings in [child results](../src/main/kotlin/ru/souz/agent/SubagentTool.kt). Cancellation and limit failures leave completed effects in place. Check existing work before retrying.
- Propagate parent cancellation. Keep child jobs and outcomes execution-local and leave durable records and recovery to the host.

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test` for runtime changes. See [registry races and limits](../src/test/kotlin/ru/souz/agent/SubagentLifecycleTest.kt), [parent cleanup and steering](../src/test/kotlin/ru/souz/ParentSubagentExecutionTest.kt), [capability selection](../../sharedLogic/src/test/kotlin/ru/souz/tool/subagent/SubagentToolFactoryTest.kt), and [backend isolation and accounting](../../backend/src/test/kotlin/ru/souz/backend/e2e/BackendSubagentE2eTest.kt).
