# Graph Engine

Before changing this module, read its [pain-point index](docs/pain-points.md) and any relevant topics.

## Purpose and boundaries

- `:graph-engine` is the reusable typed graph DSL and suspendable execution runtime.
- Keep it independent of agents, LLMs, tools, UI, and application wiring. Agent-specific adaptation belongs in `:agent`.

## Invariants

- Edge types must line up: a source node's output is the target node's input.
- Static fan-out is processed FIFO; use dynamic routing when only one branch should continue.
- Nested graphs share the active `GraphRuntime`, including step counting, retries, tracing, and cancellation state.
- Never retry coroutine cancellation. Preserve the last successful context through `GraphCancellation`, terminate through `nodeFinish`, and retain the `maxSteps` loop guard.

## Verification

```bash
./gradlew :graph-engine:test
```
