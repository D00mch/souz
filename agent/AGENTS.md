# Agent

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:agent` owns the provider-agnostic agent contracts, graph orchestration, execution state, session tracing, and skill activation.
- It depends on `:graph-engine` for graph execution and `:llms` for shared chat, tool, and provider contracts. The adapter under `ru.souz.agent.graph` is internal.
- The backend supplies settings, tools, runtime services, and other integrations through `ru.souz.agent.spi`; keep application DI and concrete backend services outside this module.
- `AgentToolCatalog` owns immutable tool membership, globally unique function names, category order, filtering, transformation, and composition. Host factories construct its values.

## Invariants

- Create a separate execution kernel and graph instance for each isolated backend execution. Do not share mutable context, continuation controllers, or session state between executions.
- `AgentContext.activeExchangeStartIndex` is execution-local. History input resets it; history edits adjust it. Compaction preserves the entire suffix, including injected context, tools, and all mid-run inputs. Do not persist the boundary or send it to providers.
- `AgentExecutionKernelFactory` builds one request-scoped, steerable skills graph and supports only `AgentId.SKILLS_GRAPH`. Unsupported persisted IDs normalize to the first configured agent. Preserve its setup order: history input, memory recall, Skill inventory, context enrichment, then LLM execution. Mid-run input returns directly to the active LLM loop without repeating setup.
- Memory recall removes structurally marked memory from the previous turn and inserts fresh memory before other turn setup. Hosts may disable `automaticMemoryRecall`; stale-memory cleanup, explicit memory tools and completed-turn capture remain independent. Completed-turn memory capture belongs to graph finalization: snapshot the accepted completed exchange and schedule capture after finalization succeeds; capture remains asynchronous and failure-isolated.
- Skill discovery uses enabled tool-backed IDs and escaped file-backed IDs. The skills graph selects concise descriptions during inventory preparation using bounded conversation, an optional host classifier, and execution-LLM fallback. An optional fallback classifier is asked only after the host classifier fails. Full bundles load on demand through `GetSkillByName`, `RunSkillCommand`, and `SkillApprovalGate` where enabled.
- Propagate coroutine cancellation. Error handling may degrade optional integrations, but must not convert cancellation into a normal result. Only an explicit child-only registry cancellation becomes `subagent_cancelled`, after checking that the parent is still active.
- Agent settings carry an explicit provider and host-resolved model ID. Update them together when selecting a model and preserve them through child and classifier requests. Classifiers accept typed chat requests; JSON round trips discard internal routing metadata. Requests carry the bounded conversation as separate messages ending with the new user message; each classifier renders it for its engine.
- `AgentSettings.routing` is pending complexity routing for a turn's first LLM call: with a `TaskComplexityClassifier`, `NodesLLM` runs it on the simple or the complex model and keeps the choice for the rest of the turn. Routing reuses inventory descriptions as untrusted hints; simple execution retains context but has no advertised or executable tools. See [model routing](docs/execution-lifecycle.md#model-routing).
- `SubagentTool` constructs one isolated context from host-supplied `Setup` and awaits a fresh `Agent` from an injected factory in a registered child job. The caller stays suspended until child cleanup finishes. `ToolLoopGraphBasedAgent` provides the default model/tool loop. Share host APIs and invocation identity, not agent instances, history, or event streams. See [subagent execution](docs/subagents.md).

## Pain points

- [Execution lifecycle](docs/execution-lifecycle.md) — cancellation, session ownership, continuation, and request-scoped kernels.
- [Subagent execution](docs/subagents.md) — parent suspension, capability isolation, and child lifecycle.
- [Skill activation](docs/skill-activation.md) — turn ordering, bundle loading, validation caching, and command exposure.
- [Skills-oriented graph](docs/skills-oriented-graph.md) — core-tool isolation, large-result offloading, and Knowledge lifetime.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

Run the module suite from the repository root:

```bash
./gradlew :agent:test
```
