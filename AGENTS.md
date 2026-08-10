# Souz

Souz is a Kotlin Multiplatform AI assistant with desktop and backend hosts over shared agent and runtime modules.

- Read and maintain this file and `docs/pain-points.md` before changing the repository.
- Keep documentation concise and current-state only. Do not write change-history phrases such as “now we do”.

## UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

## Development principles

- Prefer composition to inheritance.
- When you see that something can be done simpler, in less lines of code, removing the unnecessary abstractions, note the developer and ask questions on that.
- Abstractions only pay off, if we need the flexibility in the future. You don't know the future, developer does. Aks developer when choosing abstractions. 
- Do not mix coroutines with the JVM low level concurrency primitives such as: Volatile, Synchronize, ThreadLocal, etc).

## Module Map

- `:graph-engine` — framework-free typed graph execution.
- `:llms` — provider-agnostic LLM contracts and model identities.
- `:agent` — graph-based agent behavior, sessions, skills, and host SPIs.
- `:native` — local llama.cpp runtime and native bridge.
- `:ambientAgent` — ambient transcription semantics and local task analysis.
- `:sharedLogic` — shared JVM runtime logic, providers, tools, skills, memory, and sandboxes.
- `:sharedUI` — shared desktop UI logic, ViewModels, host ports, and Compose UI.
- `:desktopApp` — desktop composition root, OS integrations, persistence, and packaging.
- `:backend` — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Desktop entry point: `./gradlew :desktopApp:run`.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
