# Agent Module

`:agent` composes typed graphs, shared LLM contracts, host services, and Skills into an agent turn. See the [execution lifecycle](docs/execution-lifecycle.md), [Skills graph](docs/skills-oriented-graph.md), and [subagent execution](docs/subagents.md).

Desktop offers the classic graph (`graph`, the default) and Skills graph (`skills`) through a stateful facade. Backend turns use isolated Skills-graph kernels. Keep host services behind the shared SPIs and preserve each graph's discovery and tool boundaries.

See [AGENTS.md](AGENTS.md) for module ownership, host SPIs, and verification.
