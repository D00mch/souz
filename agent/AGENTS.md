# Agent

Before changing this module, read its [pain-point index](docs/pain-points.md) and the topics relevant to the change.

## Purpose and boundaries

- `:agent` owns the provider-agnostic agent contracts, graph orchestration, execution state, session tracing, and skill activation.
- It depends on `:graph-engine` for graph execution and `:llms` for shared chat, tool, and provider contracts. The adapter under `ru.souz.agent.graph` is internal.
- Hosts supply settings, tools, runtime services, and other integrations through `ru.souz.agent.spi`; keep UI, application DI, and concrete desktop/backend services outside this module.

## Invariants

- `AgentFacade` is a stateful, single-active-execution entry point. Starting a turn or changing its agent or context cancels the current execution. Model, prompt, temperature, and context-size setters currently update state in place, so callers must not use them concurrently with execution unless that lifecycle is changed and tested explicitly.
- Preserve the classic graph's turn setup order: history input, memory recall, direct-tool classification, Skill inventory/core tools, MCP tools, context enrichment, then LLM execution. The skills-oriented agent restricts every incoming context to its fixed core tools before graph execution, then runs history input, memory recall, Skill inventory, and context enrichment without classifying or injecting MCP tools.
- Memory recall removes structurally marked memory from the previous turn and inserts fresh memory before other turn setup. Completed-turn memory capture belongs to graph finalization: snapshot the turn before optional history summarization and schedule capture only after finalization succeeds; capture remains asynchronous and failure-isolated.
- Skill discovery uses compact user-scoped inventory data: enabled tool-backed Skill IDs and escaped file-backed Skill IDs only. `GetSkillByName` and generic `RunSkillCommand` load full file-backed bundles on demand and require cached or fresh approval before returning instructions or executing bundled commands. Validation identity is user-, skill-, bundle-, and policy-scoped.
- Propagate coroutine cancellation. Error handling may degrade optional integrations, but must not convert cancellation into a normal result.

## Verification

Run the module suite from the repository root:

```bash
./gradlew :agent:test
```
