# Agent Module

This package contains the standalone agent runtime extracted from `composeApp`.

## Responsibilities

- Own the graph-based agent runtime and execution flow.
- Own agent-specific models, graph/session mechanics, Lua runtime support, and node orchestration.
- Define the SPI boundary that the host application implements.

## Boundaries

- `agent` depends on shared agent-facing contracts and DTOs moved into the `:agent` module.
- `agent` does not depend on Compose UI, application DI wiring, or concrete host services from `composeApp`.
- Host integrations such as settings, telemetry/observability, MCP discovery, localization, and desktop context are accessed through `spi/`.

## Host Contract

The host application is expected to provide implementations for:

- `AgentSettingsProvider`
- `AgentToolCatalog`
- `AgentToolsFilter`
- `AgentDesktopInfoRepository`
- `AgentTelemetry`
- `AgentErrorMessages`
- `McpToolProvider`
- `DefaultBrowserProvider`

## Structure

See [AGENTS.md](/Users/dumch/work/souz/agent/src/main/kotlin/ru/souz/agent/AGENTS.md) for the package layout.
