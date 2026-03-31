# Graph Engine

`graph-engine` is a small, framework-free runtime for composing suspendable Kotlin nodes into typed execution graphs. The public API lives in `ru.souz.graph` and stays intentionally narrow: `Node`, `Graph`, `buildGraph`, `graph`, `GraphRuntime`, `RetryPolicy`, `StepInfo`, and `GraphCancellation`.

## Core API

- `Node<IN, OUT>` is the basic unit of work: `suspend fun execute(ctx: IN, runtime: GraphRuntime): OUT`.
- `Node(name) { input -> ... }` is the convenience factory for simple nodes. The generated runtime name keeps the provided label and adds a unique suffix.
- `Graph<IN, OUT>` also implements `Node<IN, OUT>`, so a graph can be used as a node inside another graph.
- `GraphBuilder<IN, OUT>` exposes `nodeInput` and `nodeFinish` sentinel nodes for graph entry and exit.
- `source.edgeTo(target)` registers a static transition. Calling it multiple times from the same source creates fan-out.
- `source.edgeTo { output -> nextNode }` registers a dynamic transition that chooses the next node from the previous output.
- `buildGraph(...)` builds a graph eagerly.
- `graph(...)` returns a lazy property delegate that builds once on first access and then caches the graph.

Use the lambda factory for most nodes. If a node needs access to `GraphRuntime`, implement `Node<IN, OUT>` directly instead of using `Node(name) { ... }`.

## Execution model

- `graph.start(seed, maxSteps, onStep)` creates a fresh `GraphRuntime` and executes the graph.
- The runner uses a FIFO queue, so sibling branches are explored breadth-first.
- A graph finishes when `nodeFinish` is executed. In practice, terminal nodes should always route explicitly to `nodeFinish`.
- `maxSteps` protects against accidental loops.
- Nested graphs reuse the same runtime, so retries, step counting, and tracing flow through subgraphs.
- `RetryPolicy(maxAttempts, shouldRetry)` decides whether a failed node should be retried. `CancellationException` is never retried.
- Coroutine cancellation is wrapped into `GraphCancellation(lastContext)`, which preserves the last successfully produced context.
- `onStep` is invoked after each successful node execution and receives `(StepInfo, node, from, to)`.
- `StepInfo.index` is the global step counter for the whole run.
- `StepInfo.currentGraphIndex` currently reflects the frame depth inside the active graph run.
- `StepInfo.graphName`, `graphDepth`, and `isSubgraph` describe the current graph scope and nested subgraph depth.

## Example

The executable example lives in `src/test/kotlin/ru/souz/graph/GraphReadmeExampleTest.kt`.
It covers:

- a typed pipeline built with `buildGraph`
- a nested subgraph used as a node
- a custom `RetryPolicy`
- step tracing through `onStep`

Run it with:

```bash
./gradlew :graph-engine:test --tests ru.souz.graph.GraphReadmeExampleTest
```

For lazy initialization inside an owning class, use `val pipeline by graph<String, String>(name = "pipeline") { ... }`.

## Guidelines

- Keep node types explicit. The target node input type must match the source node output type.
- Use dynamic routing when only one branch should continue from a node.
- Use fan-out only when it is acceptable for multiple siblings to be queued and executed.
- Keep reusable detail inside subgraphs and wire top-level graphs in business-level steps.
- Limit retries to idempotent or otherwise safe nodes.
- If you only care about the top-level flow in `onStep`, filter out `step.isSubgraph`.
