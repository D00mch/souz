# Souz

Souz is a backend-only Kotlin/JVM AI assistant with a Ktor HTTP host, graph-based agent execution, and PostgreSQL persistence.

- Read and maintain this file before changing the repository. Before editing a module, read its linked `AGENTS.md`, any applicable nested instructions, and relevant topics linked in its `Pain points` section.
- Keep runtime documentation and `docs/public-souz-contract/` concise and current-state only. Do not write change-history phrases such as “now we do”.
- Keep pre-implementation client agreements under `docs/proposals/`, explicitly marked as proposed and not implemented; exclude them from the implemented public API schemas.

## Runtime topics

- [Client tools and live commands](backend/docs/public-client-websocket.md)
- [Model routing](agent/docs/execution-lifecycle.md#model-routing) and [public waiting messages](backend/docs/execution-openapi-and-events.md#safe-change-guidance)
- [Server context configuration](backend/docs/named-llm-connections.md) and [context budget and compaction](agent/docs/skills-oriented-graph.md#invariant)
- [Subagent lifecycle](agent/docs/subagents.md) and [read-only Skill inventory](sharedLogic/docs/runtime-sandbox-and-skills.md#invariants)
- [Personality settings](backend/docs/personality-settings.md), [attachment contracts](backend/docs/attachments.md), [remote MCP servers](backend/docs/mcp.md), and [images](backend/docs/images.md)

## Development principles

- Prefer composition to inheritance.
- Prefer direct implementations and remove unnecessary abstractions. Introduce an abstraction only for a concrete requirement.
- Prefer coroutine coordination inside suspend execution. Keep JVM monitors and atomics at explicit non-suspending JVM boundaries when they are required.
- Keep provider adapters, tools, and rendering headless. Do not add local inference, GUI services, or OS-specific integrations to the backend.

## Module Map

- [`:graph-engine`](graph-engine/AGENTS.md) — framework-free typed graph execution.
- [`:llms`](llms/AGENTS.md) — provider-agnostic LLM contracts and model identities.
- [`:agent`](agent/AGENTS.md) — graph-based agent and subagent execution, sessions, Skill discovery/approval, and host SPIs.
- [`:sharedLogic`](sharedLogic/AGENTS.md) — shared JVM runtime logic, providers, classifiers, tools, skills, memory, and sandboxes.
- [`:skill-oauth-api`](skill-oauth-api/AGENTS.md) — provider-neutral Skill OAuth contracts (`SkillOAuthGateway`) with no host or persistence dependencies.
- [`:skill-oauth-impl`](skill-oauth-impl/AGENTS.md) — Postgres-backed `SkillOAuthGateway` implementation, provider token exchange, and the OAuth callback route, consumed only by `:backend`.
- [`:job-api`](job-api/AGENTS.md) — owner-scoped job management and execution contracts.
- [`:job-impl`](job-impl/AGENTS.md) — PostgreSQL job persistence, scheduling, leased workers, retries, and queue metrics, composed by `:backend`.
- [`:plugin-api`](plugin-api/AGENTS.md) — independent stateless JVM plugin contracts and invocation-bound host services.
- [`:backend`](backend/AGENTS.md) — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.
- [`build-logic`](build-logic/AGENTS.md) — included build for repository quality checks, outside the production module graph.

## Production dependency boundaries

Only these direct production project dependencies are allowed. Standard test-source-set dependencies are outside this policy. An unclassified configuration with a direct project dependency is rejected until its production or test role is explicit, and unlisted production source sets have no project dependencies.

- `:graph-engine` `main` → none.
- `:llms` `main` → none.
- `:agent` `main` → `:graph-engine`, `:llms`.
- `:skill-oauth-api` `main` → none.
- `:skill-oauth-impl` `main` → `:skill-oauth-api`.
- `:job-api` `main` → none.
- `:job-impl` `main` → `:job-api`.
- `:plugin-api` `main` → none.
- `:sharedLogic` `main` → `:agent`, `:llms`, `:skill-oauth-api`.
- `:backend` `main` → `:agent`, `:llms`, `:sharedLogic`, `:skill-oauth-api`, `:skill-oauth-impl`, `:job-api`, `:job-impl`, `:plugin-api`.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run `./gradlew souzGateFast` for repository policy, production module-boundary, and coroutine checks.
- Run `npm ci --prefix quality && ./gradlew souzDuplicationCheck` for the exact-checkout duplicate-code ratchet.
- Run `./gradlew test :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel` for the CI JVM test-and-coverage graph. PostgreSQL-backed tests require Docker and fail when it is unavailable.
- When changing quality tooling, run `./gradlew :build-logic:check`.
- For PR advisory publishing, run `node --test quality/pr_advisory_test.cjs`.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
