# Souz

Souz is a Kotlin Multiplatform AI assistant with desktop and backend hosts over shared agent and runtime modules.

- Read and maintain this file before changing the repository. Before editing a module, read its linked `AGENTS.md`, any applicable nested instructions, and relevant topics linked in its `Pain points` section.
- Follow the [documentation policy](docs/CONTRIBUTING.md#documentation) when adding pain-point topics.
- Keep runtime documentation and `docs/public-souz-contract/` concise and current-state only. Do not write change-history phrases such as “now we do”.
- Keep pre-implementation client agreements under `docs/proposals/`, explicitly marked as proposed and not implemented; exclude them from the implemented public API schemas.

## Runtime topics

- [Client tools and live commands](backend/docs/public-client-websocket.md)
- [Model routing](agent/docs/execution-lifecycle.md#model-routing) and [public waiting messages](backend/docs/execution-openapi-and-events.md#safe-change-guidance)
- [Server context configuration](backend/docs/named-llm-connections.md) and [context budget and compaction](agent/docs/skills-oriented-graph.md#invariant)
- [Subagent lifecycle](agent/docs/subagents.md) and [read-only Skill inventory](sharedLogic/docs/runtime-sandbox-and-skills.md#invariants)
- [Desktop chat and prompt ownership](sharedUI/docs/chat-execution.md), [clipboard transactions](desktopApp/docs/clipboard-transactions.md), and [local model profiles](native/docs/model-assets-and-context-budget.md#desktop-text-models)
- [External memory](backend/docs/external-memory.md)
- [Personality settings](backend/docs/personality-settings.md), [attachment contracts](backend/docs/attachments.md), [remote MCP servers](backend/docs/mcp.md), and [images](backend/docs/images.md)

Desktop selection prompts queue across sources and expire with their callers. Resolve approval actions only for the displayed source and request so stale clicks cannot affect a replacement prompt. [Selection contract](sharedLogic/src/commonJvmMain/kotlin/ru/souz/tool/SelectionApprovalSource.kt) · [Permission ownership](sharedUI/docs/chat-execution.md)

## UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

## Development principles

- Prefer composition to inheritance.
- When you see that something can be done simpler, in less lines of code, removing the unnecessary abstractions, note the developer and ask questions on that.
- Abstractions only pay off, if we need the flexibility in the future. You don't know the future, developer does. Aks developer when choosing abstractions. 
- Prefer coroutine coordination inside suspend execution. Keep JVM monitors and atomics at explicit non-suspending JVM or native boundaries when they are required.

## Module Map

- [`:graph-engine`](graph-engine/AGENTS.md) — framework-free typed graph execution.
- [`:llms`](llms/AGENTS.md) — provider-agnostic LLM contracts and model identities.
- [`:agent`](agent/AGENTS.md) — graph-based agent and subagent execution, sessions, Skill discovery/approval, and host SPIs.
- [`:native`](native/AGENTS.md) — local llama.cpp runtime and native bridge.
- [`:ambientAgent`](ambientAgent/AGENTS.md) — ambient transcription semantics and local task analysis.
- [`:sharedLogic`](sharedLogic/AGENTS.md) — shared JVM runtime logic, providers, classifiers, tools, skills, memory, and sandboxes.
- [`:skill-oauth-api`](skill-oauth-api/AGENTS.md) — provider-neutral Skill OAuth contracts (`SkillOAuthGateway`) with no host or persistence dependencies.
- [`:skill-oauth-impl`](skill-oauth-impl/AGENTS.md) — Postgres-backed `SkillOAuthGateway` implementation, provider token exchange, and the OAuth callback route, consumed only by `:backend`.
- [`:job-api`](job-api/AGENTS.md) — owner-scoped job management and execution contracts.
- [`:job-impl`](job-impl/AGENTS.md) — PostgreSQL job persistence, scheduling, leased workers, retries, and queue metrics, composed by `:backend`.
- [`:sharedUI`](sharedUI/AGENTS.md) — shared desktop UI logic, ViewModels, host ports, and Compose UI.
- [`:desktopApp`](desktopApp/AGENTS.md) — desktop composition root, OS integrations, persistence, and packaging.
- [`:plugin-api`](plugin-api/AGENTS.md) — independent stateless JVM plugin contracts and invocation-bound host services.
- [`:backend`](backend/AGENTS.md) — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.
- [`build-logic`](build-logic/AGENTS.md) — included build for repository quality checks, outside the production module graph.

## Production dependency boundaries

Only these direct production project dependencies are allowed. Standard test-source-set dependencies are outside this policy. An unclassified configuration with a direct project dependency is rejected until its production or test role is explicit, and unlisted production source sets have no project dependencies.

- `:graph-engine` `main` → none.
- `:llms` `main` → none.
- `:agent` `main` → `:graph-engine`, `:llms`.
- `:native` `main` → `:llms`.
- `:skill-oauth-api` `main` → none.
- `:skill-oauth-impl` `main` → `:skill-oauth-api`.
- `:job-api` `main` → none.
- `:job-impl` `main` → `:job-api`.
- `:plugin-api` `main` → none.
- `:sharedLogic` `commonJvmMain` → `:agent`, `:llms`, `:skill-oauth-api`; `jvmMain` → `:native`.
- `:ambientAgent` `jvmMain` → `:sharedLogic`.
- `:sharedUI` `commonJvmMain` → `:ambientAgent`, `:sharedLogic`, `:agent`, `:llms`; `jvmMain` → `:native`.
- `:backend` `main` → `:agent`, `:llms`, `:native`, `:sharedLogic`, `:skill-oauth-api`, `:skill-oauth-impl`, `:job-api`, `:job-impl`, `:plugin-api`.
- `:desktopApp` `main` → `:ambientAgent`, `:sharedLogic`, `:sharedUI`, `:agent`, `:llms`, `:native`.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run `./gradlew souzGateFast` for repository policy, production module-boundary, and coroutine checks.
- Run `npm ci --prefix quality && ./gradlew souzDuplicationCheck` for the exact-checkout duplicate-code ratchet.
- Run `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel` for the CI JVM test-and-coverage graph.
- When changing quality tooling, run `./gradlew :build-logic:check`.
- For PR advisory publishing, run `node --test quality/pr_advisory_test.cjs`.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Desktop entry point: `./gradlew :desktopApp:run`.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
