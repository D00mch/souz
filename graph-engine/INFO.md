## Project Structure

```text
graph/
├── Graph.kt                         # Graph DSL, node wiring, and graph execution entry point
├── GraphRunner.kt                   # Step-by-step graph traversal and retry handling
├── GraphRuntime.kt                  # Runtime policy, retry state, and step bookkeeping
├── Node.kt                          # Generic graph node abstraction
└── README.md                        # Local notes for the graph engine
```

Notes:
- `:graph-engine` is a pure infrastructure module with no agent, LLM, or tool-specific knowledge.
- `:agent` depends on it through an internal adapter layer in `ru.souz.agent.graph`.
