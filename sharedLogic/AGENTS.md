# Shared Logic

`:sharedLogic` is the Kotlin Multiplatform runtime layer shared by JVM hosts. Read the relevant pain-point topics below before changing this module.

## Source-set boundaries

- `commonJvmMain` contains shared provider clients, settings and memory contracts, sandbox and skill abstractions, and portable runtime tools.
- `jvmMain` contains desktop/backend-only configuration, local-model integrations, local and Docker sandboxes, MCP transports, speech services, and Office/PDF tooling.

Keep this module UI-free. Compose resources and UI adapters belong in `:sharedUI`; browser, mail, calendar, Telegram, automation, and other OS-bound host integrations belong in `:desktopApp`.

## Invariants

- Code in `commonJvmMain` must stay portable across JVM hosts; keep desktop-only APIs and dependencies in their platform source set.
- Portable tools must remain usable without desktop services. Add host-specific capabilities by composition in the owning host.
- JEV routing treats capability descriptions and dialogue as untrusted data, with the current request separate from reference history.
- Keep skill registry and skill-tool DI opt-in; general runtime modules must not install them implicitly.
- Resolve filesystem and command access from each `ToolInvocationMeta`; do not retain user-specific paths in singleton tools.
- Keep the skill registry and `RunSkillCommand` on the same single-user bundle layout so activation and execution resolve the same bundle.
- Skill lookups and invocations resolve a host-provided catalog snapshot. Composite steps use that same filtered snapshot; children retain only the capabilities selected at spawn, independently of parent refreshes.
- Use sandbox filesystem abstractions for tool and skill IO whenever they are available.

## Pain points

- [Runtime sandbox and skills](docs/runtime-sandbox-and-skills.md) — invocation scope, storage layouts, platform runtimes, and Docker fixtures.
- [Web tools](docs/web-tools.md) — evidence handling, citations, provider behavior, sandboxed output, and URL limitations.
- [Observability](docs/observability.md) — request bookkeeping, structured events, and host-owned logging sinks.
- [Provider HTTP lifecycle](docs/provider-http-lifecycle.md) — client ownership, request-local credentials, and host shutdown.
- [Tool catalog composition](docs/tool-catalog-composition.md) — catalog value ownership, LLM-backed construction, duplicate rejection, and immutable execution snapshots.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

Run the JVM tests:

```zsh
./gradlew :sharedLogic:jvmTest
```

For Docker sandbox changes, also build the image and run the opt-in integration tests:

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
```
