## Project Structure

```text
graph-engine/
├── src/main/kotlin/ru/souz/graph/
│   ├── Graph.kt                       # Graph DSL, node wiring, and graph execution entry point
│   ├── GraphRunner.kt                 # Step-by-step graph traversal and retry handling
│   ├── GraphRuntime.kt                # Runtime policy, retry state, and step bookkeeping
│   └── Node.kt                        # Generic graph node abstraction
├── src/test/kotlin/ru/souz/graph/
│   └── GraphReadmeExampleTest.kt      # Executable example used by the README
├── AGENTS.md                          # Local notes for the graph engine
└── README.md                          # Public module overview
```

Notes:
- `:graph-engine` is a pure infrastructure module with no agent, LLM, or tool-specific knowledge.
- `:agent` depends on it through an internal adapter layer in `ru.souz.agent.graph`.
