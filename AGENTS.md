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
- Treat JVM `ThreadLocal` state as review-only. Every declaration requires an explicit
  `@Suppress("ThreadLocalInCoroutineCode")`; coroutine access must also propagate it with
  `asContextElement`.
- Prefer coroutine coordination inside suspend execution. Keep JVM monitors and atomics at explicit non-suspending JVM or native boundaries when they are required.

## Module Map

- `:graph-engine` — framework-free typed graph execution.
- `:llms` — provider-agnostic LLM contracts and model identities.
- `:agent` — graph-based agent behavior, sessions, skills, and host SPIs.
- `:native` — local llama.cpp runtime and native bridge.
- `:ambientAgent` — ambient transcription semantics and local task analysis.
- `:sharedLogic` — shared JVM runtime logic, providers, tools, skills, memory, and sandboxes.
- `:skill-oauth-api` — provider-neutral Skill OAuth contracts (`SkillOAuthGateway`) with no host or persistence dependencies.
- `:skill-oauth-impl` — Postgres-backed `SkillOAuthGateway` implementation, provider token exchange, and the OAuth callback route, consumed only by `:backend`.
- `:sharedUI` — shared desktop UI logic, ViewModels, host ports, and Compose UI.
- `:desktopApp` — desktop composition root, OS integrations, persistence, and packaging.
- `:backend` — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.

## Production dependency boundaries

Only these direct production project dependencies are allowed. Standard test-source-set dependencies are outside this policy. An unclassified configuration with a direct project dependency is rejected until its production or test role is explicit, and unlisted production source sets have no project dependencies.

- `:graph-engine` `main` → none.
- `:llms` `main` → none.
- `:agent` `main` → `:graph-engine`, `:llms`.
- `:native` `main` → `:llms`.
- `:skill-oauth-api` `main` → none.
- `:skill-oauth-impl` `main` → `:skill-oauth-api`.
- `:sharedLogic` `commonJvmMain` → `:agent`, `:llms`, `:skill-oauth-api`; `jvmMain` → `:native`.
- `:ambientAgent` `jvmMain` → `:sharedLogic`.
- `:sharedUI` `commonJvmMain` → `:ambientAgent`, `:sharedLogic`, `:agent`, `:llms`; `jvmMain` → `:native`.
- `:backend` `main` → `:agent`, `:llms`, `:native`, `:sharedLogic`, `:skill-oauth-api`, `:skill-oauth-impl`.
- `:desktopApp` `main` → `:ambientAgent`, `:sharedLogic`, `:sharedUI`, `:agent`, `:llms`, `:native`.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run `./gradlew souzGateFast` for repository policy, production module-boundary, and coroutine checks.
- Run `npm ci --prefix quality && ./gradlew souzDuplicationCheck` for the exact-checkout duplicate-code ratchet.
- Run `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport :koverLog -Psouz.coverage --no-parallel` for the CI JVM test-and-coverage graph.
- When changing quality tooling, run `./gradlew :build-logic:check`.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Desktop entry point: `./gradlew :desktopApp:run`.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
