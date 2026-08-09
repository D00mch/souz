# Agent Module

`:agent` contains the provider-agnostic runtime that composes typed graph execution, shared LLM contracts, host services, and optional skills into an agent turn.

## Responsibilities

- Own agent contracts, context construction, graph orchestration, execution, session tracing, and skill activation.
- Adapt `:graph-engine` to agent state and use the shared request, response, and tool contracts from `:llms`.
- Define the SPI boundary implemented by desktop, backend, and test hosts.

## Boundaries

- Keep Compose UI, application DI, and concrete host services in their owning modules.
- Supply settings, tools, telemetry, localization, MCP discovery, and runtime context through `ru.souz.agent.spi` rather than adding host dependencies.
- Use `AgentFacade` for a stateful single-user conversation. `AgentExecutionKernelFactory` builds an isolated, steerable `AgentId.GRAPH` execution from direct host tools; configured always-available tool names remain advertised after classification and throughout mid-run continuations.

See [AGENTS.md](AGENTS.md) for maintenance constraints and verification guidance.
