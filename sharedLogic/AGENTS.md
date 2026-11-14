# Shared Logic

`:sharedLogic` is the Kotlin Multiplatform runtime layer shared by JVM hosts.

## Source-set boundaries

- `commonJvmMain` owns shared provider clients, settings and memory contracts, sandbox and Skill abstractions, and portable runtime tools.
- `jvmMain` owns platform configuration, local models, local and Docker sandboxes, MCP transports, speech services, and Office/PDF tooling.
- Keep Compose and UI adapters in `:sharedUI`, and OS-bound integrations in `:desktopApp`.

## Purpose and boundaries

- Keep application lifecycle, request ownership, persistence composition, and HTTP routes in `:backend`.
- Keep portable tools usable without desktop services. Keep runtime tools headless and resolve per-execution capabilities through the host catalog. Keep chart rendering independent of parsing, paths, and sandbox checks.
- Keep Skill registry and Skill tool DI opt-in.
- Use available sandbox filesystem abstractions for tool and Skill IO. Resolve access from the current `ToolInvocationMeta`.

## Pain points

- [MCP servers](../backend/docs/mcp.md) — SDK schemas and results, dedicated transport, bounded discovery and session lifetime.
- [Runtime sandbox and skills](docs/runtime-sandbox-and-skills.md) — invocation scope, read-only inventory, bundle layout, and command runtimes.
- [Web tools](docs/web-tools.md) — evidence handling, citations, provider behavior, sandboxed output, and URL limitations.
- [Images](../backend/docs/images.md) — headless validation, request-local pixels and provider generation/editing.
- [Observability](docs/observability.md) — request bookkeeping, structured events, and host-owned logging sinks.
- [Provider HTTP lifecycle](docs/provider-http-lifecycle.md) — client ownership, request-local credentials, and host shutdown.
- [Tool catalog composition](docs/tool-catalog-composition.md) — catalog value ownership, LLM-backed construction, duplicate rejection, and immutable execution snapshots.
- [Model routing](../agent/docs/execution-lifecycle.md#model-routing) — current request, reference history, and untrusted capability hints.

## Verification

Run `./gradlew :sharedLogic:jvmTest`.

For Docker sandbox changes, also run both commands.

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
```
