# Souz

Souz is a backend-only Kotlin/JVM AI assistant with a Ktor HTTP host, graph-based agent execution, and PostgreSQL persistence.

Backend client Skills are bundled under `backend/src/main/resources/skills/client/`. Orion command tools come from chat declarations or discovery. Chat declarations override matching defaults; discovered Orion tools override both and remain bound to their supplying channel until refreshed in that conversation.

Backend uses one startup-validated `CONTEXT_SIZE` (minimum 24,000 tokens) for all models, including summaries. Compaction and input validation share an 80% limit independent of output limits; context size is not a user or execution setting.

Backend [personality settings](backend/docs/personality-settings.md) are separate from system prompts and synchronize revisioned snapshots to Orion.

- Read and maintain this file before changing the repository. Before editing a module, read its linked `AGENTS.md`, any applicable nested instructions, and relevant topics linked in its `Pain points` section.
- Keep runtime documentation and `docs/public-souz-contract/` concise and current-state only. Do not write change-history phrases such as “now we do”.
- Keep pre-implementation client agreements under `docs/proposals/`, explicitly marked as proposed and not implemented; exclude them from the implemented public API schemas.

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
- [`:job-impl`](job-impl/AGENTS.md) — PostgreSQL job persistence, scheduling, leased workers, and retries, composed by `:backend`.
- [`:backend`](backend/AGENTS.md) — trusted-proxy HTTP host and PostgreSQL-backed conversation runtime.
- [`build-logic`](build-logic/AGENTS.md) — included build for repository quality checks, outside the production module graph.
- [`:plugin-api`](plugin-api/AGENTS.md) — public trusted-plugin SDK.
- [`:plugin-runtime`](plugin-runtime/AGENTS.md) — JAR loading and generation leases.
- [`:plugins`](plugins/AGENTS.md) — independently packaged examples.
- [`:plugins:saved-links`](plugins/saved-links/AGENTS.md) — saved-links example JAR.
- [`:plugins:gmail`](plugins/gmail/AGENTS.md) — read-only Gmail and notification workflow JAR.
- [`:plugins:github`](plugins/github/AGENTS.md) — public GitHub repository, issue, PR, CI, and release reads.

## Production dependency boundaries

Only these direct production project dependencies are allowed. Standard test-source-set dependencies are outside this policy. An unclassified configuration with a direct project dependency is rejected until its production or test role is explicit, and unlisted production source sets have no project dependencies.

- `:plugin-api` `main` → none.
- `:plugin-runtime` `main` → `:plugin-api`.
- `:plugins` `main` → none.
- `:plugins:saved-links` `main` → `:plugin-api`.
- `:plugins:gmail` `main` → `:plugin-api`.
- `:plugins:github` `main` → `:plugin-api`.
- `:graph-engine` `main` → none.
- `:llms` `main` → none.
- `:agent` `main` → `:graph-engine`, `:llms`.
- `:skill-oauth-api` `main` → none.
- `:skill-oauth-impl` `main` → `:skill-oauth-api`.
- `:job-api` `main` → none.
- `:job-impl` `main` → `:job-api`.
- `:sharedLogic` `main` → `:agent`, `:llms`, `:skill-oauth-api`.
- `:backend` `main` → `:agent`, `:llms`, `:sharedLogic`, `:skill-oauth-api`, `:skill-oauth-impl`, `:job-api`, `:job-impl`, `:plugin-api`, `:plugin-runtime`.

## Verification

- Use the Gradle wrapper and the Java 21 toolchain configured by the build.
- Run `./gradlew souzGateFast` for repository policy, production module-boundary, and coroutine checks.
- Run `npm ci --prefix quality && ./gradlew souzDuplicationCheck` for the exact-checkout duplicate-code ratchet.
- Run `./gradlew test :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel` for the CI JVM test-and-coverage graph. PostgreSQL-backed tests require Docker and fail when it is unavailable.
- When changing quality tooling, run `./gradlew :build-logic:check`.
- Run the affected module's command from its `AGENTS.md`; use `./gradlew check` for repository-wide verification when the change warrants it.
- Backend entry point: `./gradlew :backend:run`.
- For documentation-only changes, validate local links and run `git diff --check`; runtime tests are unnecessary unless source behavior also changes.
