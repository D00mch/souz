# Graph Engine

## Purpose and boundaries

- `:graph-engine` is the reusable typed graph DSL and suspendable execution runtime.
- Keep it independent of agents, LLMs, tools, UI, and application wiring. Agent-specific adaptation belongs in `:agent`.
- See the [execution model](README.md#execution-model) and [graph guidelines](README.md#guidelines).

## Pain points

No module-specific pain-point topics are currently recorded.

## Verification

Run `./gradlew :graph-engine:test`.
