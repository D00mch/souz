# Graph Engine

`graph-engine` is a framework-free runtime for composing suspendable Kotlin nodes into typed execution graphs. Build a graph eagerly with `buildGraph` or lazily with the `graph` property delegate. A graph is itself a node and can run inside another graph. [Graph API](src/main/kotlin/ru/souz/graph/Graph.kt) · [Node API](src/main/kotlin/ru/souz/graph/Node.kt)

## Execution model

- `edgeTo(target)` wires a static route. `edgeTo { output -> nextNode }` chooses a route from the node output. Multiple edges enqueue fan-out in registration order and run breadth-first. Reaching `nodeFinish` ends that graph even if siblings remain queued.
- Each run has a fresh runtime. Subgraphs share retries, tracing, and the `maxSteps` loop guard.
- Cancellation bypasses retries. A `CancellationException` caught by the runner is wrapped in `GraphCancellation` with the last context that runner recorded. [Runner](src/main/kotlin/ru/souz/graph/GraphRunner.kt) · [Trace fields](src/main/kotlin/ru/souz/graph/GraphRuntime.kt)

## Example

Run the typed pipeline with dynamic routing, a nested graph, custom retries, and tracing. [Executable example](src/test/kotlin/ru/souz/graph/GraphReadmeExampleTest.kt)

```bash
./gradlew :graph-engine:test --tests ru.souz.graph.GraphReadmeExampleTest
```

For lazy initialization inside an owning class, use `val pipeline by graph<String, String>(name = "pipeline") { ... }`.

## Guidelines

- Match the target input type to the source output and route every terminal branch to `nodeFinish`. Use a single dynamic edge when only one branch should continue.
- Keep reusable detail inside subgraphs and wire top-level graphs in business-level steps.
- Limit retries to idempotent or otherwise safe nodes.
- Filter out `step.isSubgraph` to trace only the top-level flow.
