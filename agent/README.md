# Agent Module

`:agent` contains the provider-agnostic runtime that composes typed graph execution, shared LLM contracts, host services, and optional skills into an agent turn.

## Responsibilities

- Own agent contracts, context construction, graph orchestration, execution, session tracing, and skill activation.
- Adapt `:graph-engine` to agent state and use the shared request, response, and tool contracts from `:llms`.
- Define the SPI boundary implemented by backend services and test fixtures.

## Boundaries

- Keep application DI and concrete backend services in their owning modules.
- Supply settings, tools, telemetry, localization, Skill discovery, and runtime context through `ru.souz.agent.spi` rather than adding host dependencies.
- `AgentExecutionKernelFactory` builds an isolated, steerable `AgentId.SKILLS_GRAPH` execution. Its fixed core Skill tools discover capabilities from the request-scoped catalog, and unsupported persisted IDs normalize to the configured skills graph.
- `ToolLoopGraphBasedAgent` runs a bounded subagent model/tool loop. Each execution uses its own context, graph, and continuation state.

See [AGENTS.md](AGENTS.md) for maintenance constraints and verification guidance.
