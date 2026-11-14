# Agent

## Purpose and boundaries

- `:agent` owns the provider-agnostic agent contracts, graph orchestration, execution state, session tracing, and skill activation.
- Keep the graph adapter internal. Hosts supply settings, tools, and runtime services through [agent SPIs](src/main/kotlin/ru/souz/agent/spi). Keep UI, application DI, and concrete desktop/backend services outside this module.
- Use the host runtime environment's clock and time zone for context dates and UTC offsets.

## Invariants

- Give each execution its own kernel, graph, mutable context, continuation controller, and session state.
- Keep the stateful desktop facade single-active-execution. Follow its [lifecycle rules](docs/execution-lifecycle.md#invariant) when changing context or settings.
- Propagate coroutine cancellation through optional integrations and error handling.
- Keep the explicit provider and host-resolved model ID together in agent settings, including child and classifier requests. Use typed chat requests to preserve internal routing metadata through classifier calls.

## Pain points

- [Execution lifecycle](docs/execution-lifecycle.md) — continuation, stream revisions, model routing, and memory recall and capture.
- [Subagent execution](docs/subagents.md) — asynchronous orchestration, capability isolation, and child lifecycle.
- [Skill activation](docs/skill-activation.md) — turn setup, inventory selection, bundle loading, and approval.
- [Skills-oriented graph](docs/skills-oriented-graph.md) — core-tool isolation, compaction, large-result offloading, and Knowledge lifetime.
- [Tool catalog composition](../sharedLogic/docs/tool-catalog-composition.md) — immutable membership, unique names, and host composition.

## Verification

Run `./gradlew :agent:test`.
