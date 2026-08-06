# Shared Logic

`:sharedLogic` is the Kotlin Multiplatform runtime layer shared by JVM hosts. Read the [module pain-point index](docs/pain-points.md) and every topic relevant to the code being changed.

## Source-set boundaries

- `commonJvmMain` contains shared provider clients, settings and memory contracts, sandbox and skill abstractions, and portable runtime tools.
- `jvmMain` contains desktop/backend-only configuration, local-model integrations, local and Docker sandboxes, MCP transports, speech services, and Office/PDF tooling.

Keep this module UI-free. Compose resources and UI adapters belong in `:sharedUI`; browser, mail, calendar, Telegram, automation, and other OS-bound host integrations belong in `:desktopApp`.

## Invariants

- Code in `commonJvmMain` must stay portable across JVM hosts; keep desktop-only APIs and dependencies in their platform source set.
- Portable tools must remain usable without desktop services. Add host-specific capabilities by composition in the owning host.
- Resolve filesystem and command access from each `ToolInvocationMeta`; do not retain user-specific paths in singleton tools.
- Keep skill registry storage scope and `RunSkillCommand` storage scope aligned so activation and execution resolve the same bundle.
- Use sandbox filesystem abstractions for tool and skill IO whenever they are available.

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
