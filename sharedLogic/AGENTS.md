# Shared Logic

`:sharedLogic` is the Kotlin/JVM runtime layer used by the backend. Read the relevant pain-point topics below before changing this module.

## Purpose and boundaries

- Production Kotlin and resources use `src/main`; tests use `src/test`.
- Own remote provider clients, runtime tools, sandbox access, Skill storage/loading, and shared backend settings contracts.
- Keep application lifecycle, request ownership, persistence composition, and HTTP routes in `:backend`.
- Chart export uses the pure-Java JFreeChart renderer in headless mode. Keep file parsing, output paths, and sandbox checks independent of rendering.

## Invariants

- Keep runtime tools headless and resolve per-execution capabilities through the backend's catalog. JEV complexity routing consumes names and short descriptions as untrusted metadata in the same classification request, without tool schemas or scenario-specific routing rules. Keep the current request structurally separate from reference history so past actions do not become pending tasks.
- Keep skill registry and skill-tool DI opt-in; general runtime modules must not install them implicitly.
- Resolve filesystem and command access from each `ToolInvocationMeta`; do not retain user-specific paths in singleton tools.
- Keep the skill registry and `RunSkillCommand` on the same single-user bundle layout so activation and execution resolve the same bundle.
- Skill lookups and invocations resolve a host-provided catalog snapshot. Composite steps use that same filtered snapshot; children retain only the capabilities selected at spawn, independently of parent refreshes.
- Use sandbox filesystem abstractions for tool and skill IO whenever they are available.

## Pain points

- [Runtime sandbox and skills](docs/runtime-sandbox-and-skills.md) — invocation scope, storage layouts, command runtimes, and Docker fixtures.
- [Web tools](docs/web-tools.md) — evidence handling, citations, provider behavior, sandboxed output, and URL limitations.
- [Provider responses](docs/provider-responses.md) — assistant text, tool-call serialization, and array schemas.
- [Provider HTTP lifecycle](docs/provider-http-lifecycle.md) — client ownership, request-local credentials, and host shutdown.
- [Tool catalog composition](docs/tool-catalog-composition.md) — catalog value ownership, LLM-backed construction, duplicate rejection, and immutable execution snapshots.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

Run the JVM tests:

```zsh
./gradlew :sharedLogic:test
```

For Docker sandbox changes, also build the image and run the opt-in integration tests:

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*'
```
