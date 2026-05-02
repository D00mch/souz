## Project Structure

```text
GraphBasedAgent.kt                    # Standard tool-calling graph agent
LuaGraphBasedAgent.kt                 # Lua-planning graph agent
agent/
   ├── Agent.kt                       # Public agent contracts and execution result models
   ├── AgentContextFactory.kt         # Stateless initial-context builder from host contracts
   ├── AgentExecutor.kt               # Stateless graph execution entry point
   ├── AgentFacade.kt                 # Active-agent selection, context lifecycle, and top-level execution entry point
   ├── AgentId.kt                     # Supported agent identifiers and defaults
   ├── AgentModule.kt                 # Single DI module that wires agent internals
   ├── SystemPromptResolver.kt        # Default system prompt selection by agent/model/profile
   ├── TraceableAgent.kt              # Internal tracing contract used by concrete agents
   ├── graph/                         # Internal adapter from agent state to :graph-engine
   ├── skills/                        # Skill bundle selection, validation, activation, and registry storage
   │   ├── SkillActivationPipeline.kt # Explicit skill-selection/validation/activation state machine
   │   ├── activation/                # Activated-skill models, skill ids, and history/context injection
   │   ├── bundle/                    # Skill bundle parsing, normalization, and canonical hashing
   │   ├── registry/                  # User-scoped stored skill metadata and validation-cache repository contracts
   │   ├── selection/                 # Metadata-based and LLM-backed skill selection strategies
   │   └── validation/                # Structural, static, and LLM validation with persisted verdict records
   ├── spi/                           # Host-facing service provider interfaces
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
   │   ├── AgentToolExecutor.kt       # Tool invocation bridge with structured telemetry logging
   │   ├── GraphExecutionDelegate.kt  # Active job tracking and traced graph execution
   │   └── LuaRuntime.kt              # Sandboxed Lua runtime with exposed tools
   ├── session/                       # Persisted graph session models and services
   │   ├── GraphSession.kt            # Session and per-step persistence models
   │   ├── GraphSessionRepository.kt  # Filesystem-backed session storage
   │   └── GraphSessionService.kt     # Session lifecycle and per-step capture
db/                                   # Shared stored-data model used by prompt enrichment
tool/                                 # Shared tool enums and classifier contract
```

Notes:
- `:agent` contains both the public agent contract layer and the concrete implementations.
- `:graph-engine` is the lower-level boundary; the `agent/graph` package is an internal adapter over it.
- The `skills` package owns standalone skill-bundle activation: selection runs on stored metadata, full bundles load only for selected ids, and validation approvals are cached by user + skill id + bundle hash + policy version.
- Shared `ru.souz.llms` DTOs and chat/tool contracts now live in the separate `:llms` module.
- `AgentFacade` remains the intended stateful desktop entry point, now delegating to `AgentContextFactory` and `AgentExecutor`.
