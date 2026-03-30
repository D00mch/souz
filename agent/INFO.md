## Project Structure

```text
GraphBasedAgent.kt                   # Standard tool-calling graph agent
LuaGraphBasedAgent.kt                # Lua-planning graph agent
agent/
├── Agent.kt                          # Agent contracts, side effects, and execution result models
├── AgentFacade.kt                    # Active-agent selection, context lifecycle, and top-level execution entry point
├── AgentId.kt                        # Supported agent identifiers and defaults
├── SystemPromptResolver.kt           # Default system prompt selection by agent/model/profile
├── README.md                         # Package overview and host boundary notes
├── INFO.md                           # This file
├── engine/                           # Graph primitives and runner/runtime contracts
│   ├── AgentContext.kt               # Immutable agent context and tool/settings wrappers
│   ├── Graph.kt                      # Graph DSL, node wiring, and graph execution entry point
│   ├── GraphRunner.kt                # Step-by-step graph traversal and retry handling
│   ├── GraphRuntime.kt               # Runtime policy, retry state, and step bookkeeping
│   ├── Node.kt                       # Generic graph node abstraction
│   └── README.md                     # Local notes for the graph engine
├── nodes/                            # Graph node implementations
│   ├── NodesClassification.kt        # Category classification and tool narrowing
│   ├── NodesCommon.kt                # History shaping, tool execution, and prompt enrichment
│   ├── NodesErrorHandling.kt         # User-facing error mapping
│   ├── NodesLLM.kt                   # LLM request and streaming response handling
│   ├── NodesLua.kt                   # Lua planning, execution, and repair loop nodes
│   ├── NodesMCP.kt                   # MCP tool injection node
│   └── NodesSummarization.kt         # History summarization and save-point logic
├── runtime/                          # Execution helpers used by agent nodes/impls
│   ├── AgentToolExecutor.kt          # Tool invocation bridge with telemetry hooks
│   ├── GraphExecutionDelegate.kt     # Active job tracking and traced graph execution
│   └── LuaRuntime.kt                 # Sandboxed Lua runtime with exposed tools
├── session/                          # Persisted graph session models and services
│   ├── GraphSession.kt               # Session and per-step persistence models
│   ├── GraphSessionRepository.kt     # Filesystem-backed session storage
│   └── GraphSessionService.kt        # Session lifecycle and per-step capture
└── spi/                              # Interfaces implemented by the host application
    ├── AgentDesktopInfoRepository.kt # Desktop context search contract
    ├── AgentErrorMessages.kt         # Localized agent error strings
    ├── AgentSettingsProvider.kt      # Settings needed by the runtime
    ├── AgentTelemetry.kt             # Tool execution telemetry sink
    ├── AgentToolCatalog.kt           # Full host-defined tool catalog
    ├── AgentToolsFilter.kt           # Host-side tool filtering/customization
    ├── DefaultBrowserProvider.kt     # Default browser display-name lookup
    └── McpToolProvider.kt            # Dynamic MCP tool provider
```

Notes:
- This package is the main entry point of the `:agent` module.
- The host app should wire concrete implementations through `spi/` rather than reintroducing direct dependencies from this package back to `composeApp`.
- `AgentFacade` is the intended entry point for UI and service layers that need to run the agent.
