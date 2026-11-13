# Shared Logic

## Purpose and boundaries

- `:sharedLogic` owns JVM provider adapters, classifiers, runtime tools, sandbox access, Skill storage and loading, and shared backend settings contracts.
- Keep application lifecycle, request ownership, persistence composition, and HTTP routes in `:backend`.
- Keep runtime tools headless and resolve per-execution capabilities through the host catalog. Keep chart rendering independent of parsing, paths, and sandbox checks.
- Keep Skill registry and Skill tool DI opt-in.
- Use available sandbox filesystem abstractions for tool and Skill IO. Resolve access from the current `ToolInvocationMeta`.

## Pain points

- [MCP servers](../backend/docs/mcp.md) — SDK schemas and results, dedicated transport, bounded discovery and session lifetime.
- [Runtime sandbox and skills](docs/runtime-sandbox-and-skills.md) — invocation scope, read-only inventory, bundle layout, and command runtimes.
- [Web tools](docs/web-tools.md) — evidence handling, citations, provider behavior, sandboxed output, and URL limitations.
- [Images](../backend/docs/images.md) — headless validation, request-local pixels and provider generation/editing.
- [Provider responses](docs/provider-responses.md) — assistant text, tool-call serialization, and array schemas.
- [Provider HTTP lifecycle](docs/provider-http-lifecycle.md) — client ownership, request-local credentials, and host shutdown.
- [Tool catalog composition](docs/tool-catalog-composition.md) — catalog value ownership, LLM-backed construction, duplicate rejection, and immutable execution snapshots.
- [Model routing](../agent/docs/execution-lifecycle.md#model-routing) — current request, reference history, and untrusted capability hints.

## Verification

Run `./gradlew :sharedLogic:test`.

For Docker sandbox changes, also run both commands.

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*'
```
