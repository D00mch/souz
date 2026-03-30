## Project Structure

```text
GraphBasedAgent.kt                    # Standard tool-calling graph agent
LuaGraphBasedAgent.kt                 # Lua-planning graph agent
agent/
   ├── Agent.kt                       # Public agent contracts and execution result models
   ├── AgentFacade.kt                 # Active-agent selection, context lifecycle, and top-level execution entry point
   ├── AgentId.kt                     # Supported agent identifiers and defaults
   ├── AgentModule.kt                 # Single DI module that wires agent internals
   ├── SystemPromptResolver.kt        # Default system prompt selection by agent/model/profile
   ├── TraceableAgent.kt              # Internal tracing contract used by concrete agents
   ├── graph/                         # Internal adapter from agent state to :graph-engine
   ├── spi/                           # Host-facing interfaces implemented by composeApp
   ├── state/                         # AgentContext and related state/settings wrappers
   ├── nodes/                         # Graph node implementations
   │   ├── NodesClassification.kt     # Category classification and tool narrowing
   │   ├── NodesCommon.kt             # History shaping, tool execution, and prompt enrichment
   │   ├── NodesErrorHandling.kt      # User-facing error mapping
   │   ├── NodesLLM.kt                # LLM request and streaming response handling
   │   ├── NodesLua.kt                # Lua planning, execution, and repair loop nodes
   │   ├── NodesMCP.kt                # MCP tool injection node
   │   └── NodesSummarization.kt      # History summarization and save-point logic
   ├── runtime/                       # Execution helpers used by agent nodes/impls
   │   ├── AgentToolExecutor.kt       # Tool invocation bridge with telemetry hooks
   │   ├── GraphExecutionDelegate.kt  # Active job tracking and traced graph execution
   │   └── LuaRuntime.kt              # Sandboxed Lua runtime with exposed tools
   ├── session/                       # Persisted graph session models and services
   │   ├── GraphSession.kt            # Session and per-step persistence models
   │   ├── GraphSessionRepository.kt  # Filesystem-backed session storage
   │   └── GraphSessionService.kt     # Session lifecycle and per-step capture
db/                                   # Shared stored-data model used by prompt enrichment
llms/                                 # Shared LLM DTOs and chat/tool contracts
tool/                                 # Shared tool enums and classifier contract
```

Notes:
- `:agent` contains both the public agent contract layer and the concrete implementations.
- `:graph-engine` is the lower-level boundary; the `agent/graph` package is an internal adapter over it.
- `AgentFacade` is the intended entry point for UI and service layers that need to run the agent.
