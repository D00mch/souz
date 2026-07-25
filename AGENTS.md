# Souz

Souz is a Kotlin Multiplatform AI assistant with desktop, Android, and backend hosts over shared agent and runtime modules.

## Working Agreement

- Read this file before changing the repository.
- For every module you touch, read that module's `AGENTS.md` before editing it.
- Read [`docs/pain-points.md`](docs/pain-points.md), then only the module topics relevant to the area you will change.
- Keep documentation concise and current-state only. Update the smallest owning document instead of copying the same fact across files.
- Put durable instructions, ownership boundaries, and verification commands in `AGENTS.md`.
- Put non-obvious invariants, failure modes, and safe-change guidance in pain-point topics.
- Put human-facing architecture and usage descriptions in READMEs. Exact routes, config keys, constants, and file inventories should come from source code or generated documentation.

## Engineering Principles

- Keep screens and composables presentation-only. Coordinate UI behavior in ViewModels and delegate domain work to use cases.
- Prefer direct composition and the simplest design that satisfies known requirements.
- Before adding an abstraction justified by possible future flexibility, explain the concrete extension point and ask the developer.
- In coroutine-managed code, prefer coroutine coordination primitives over blocking JVM synchronization. Isolate unavoidable JVM or native synchronization at adapter boundaries.
- Preserve module and source-set boundaries; do not solve placement problems with platform checks inside shared code.

## Module Map

- `:graph-engine` — framework-free typed graph execution.
- `:llms` — provider-agnostic LLM contracts and model identities.
- `:agent` — graph-based agent behavior, sessions, skills, and host SPIs.
- `:native` — local llama.cpp runtime and native bridge.
- `:ambientAgent` — ambient transcription semantics and local task analysis.
- `:sharedLogic` — Android/JVM shared runtime logic, providers, tools, skills, memory, and sandboxes.
- `:sharedUI` — Android/Desktop presentation logic, ViewModels, host ports, and Compose UI.
- `:desktopApp` — desktop composition root, OS integrations, persistence, and packaging.
- `:androidApp` — Android application host and platform bindings.
- `:backend` — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Desktop entry point: `./gradlew :desktopApp:run`.
- Android package: `./gradlew :androidApp:assembleDebug`.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
