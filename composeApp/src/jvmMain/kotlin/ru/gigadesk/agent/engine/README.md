# Agent engine (graph-based)

This package implements a minimal, framework-free agent engine built around a graph of nodes, inspired by the ideas in the article
[“Агент на Kotlin без фреймворков”](https://habr.com/ru/articles/958468/). It focuses on a small, composable core: immutable
context, explicit transitions, and deterministic execution with retries.

## Key concepts

- **AgentContext** — immutable payload that flows through the graph: input, settings, conversation history, active tools, and
  a system prompt. Nodes return a *new* context instead of mutating state.
- **Node** — a suspendable unit of work (`execute`) that transforms `AgentContext<IN>` into `AgentContext<OUT>`.
- **Graph** — a Node that wires other nodes with **static** or **dynamic** transitions (`edgeTo`). Executable with GraphRunner.
- **GraphRunner** — execution engine that traverses the graph, respects `maxSteps`, and stops on a designated exit node.
- **GraphRuntime & RetryPolicy** — runtime settings for retries, step limits, and step callbacks.

## Usage example

```kotlin
val userInput = Node<String, String>("userInput") { ctx -> ctx.map { readln() } }
val llmCall = Node<String, String>("llmCall") { ctx -> ctx.map { "Response: $it" } }

val graph = buildGraph(name = "chat") {
  nodeInput.edgeTo(userInput)
  userInput.edgeTo { ctx -> if (ctx.input == "exit") nodeFinish else llmCall }
  llmCall.edgeTo(nodeFinish)
}

val seed = AgentContext(
  input = "hello",
  settings = AgentSettings(model = "gpt", temperature = 0.2f, toolsByCategory = emptyMap()),
  history = emptyList(),
  activeTools = emptyList(),
  systemPrompt = "",
)

println("Result: ${graph.start(seed)}")
```

## Best practices

- When you create a **Node**, document what input it expects, what output produce (e.g. takes `ctx.input`, updates `ctx.history`). 
- When working with **dynamic** transitions — `edge { ... }` — we have to `edge` from the same type.
- In the top level graph, use the combined nodes to avoid low level details.