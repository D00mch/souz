# Souz

Souz is a Kotlin Multiplatform AI assistant with desktop and backend hosts over shared agent and runtime modules.

Backend client Skill guidance is bundled under `backend/src/main/resources/skills/client/`. Only `orion.tools.list` is injected into every public WebSocket channel. Declarations and discovery describe that channel’s tools; all conversations can use their user’s unarchived channels. Discovery overrides matching declarations on its provider. Equal definitions share one Skill with provider metadata; conflicting names and server control commands are hidden from the model. `user.ask` is supported when declared or discovered. Local calls default to the current channel; explicit routing takes priority, and only model-callable `web.search` can automatically select another provider. Discovery is explicit and persists across backend restarts, including successful empty catalogues.

Complexity routing admits only confident tool-free turns to the light model. Public-client executions attempt one best-effort live waiting phrase unless routed simple or `intermediateMessages` is off; progress or completion can overtake it. See [model routing](agent/docs/execution-lifecycle.md#model-routing).

Backend uses one startup-validated `CONTEXT_SIZE` (minimum 24,000 tokens) for all models, including summaries. Compaction and input validation share an 80% limit independent of output limits; context size is not a user or execution setting.

Backend provider attempts share per-user admission, request/token quotas and provider concurrency across parents and children. Concurrency admission waits cancellably for capacity; request and token quotas still reject excess calls. Subagents run asynchronously with admission limits and cooperative host deadlines (ten minutes by default; backend `SUBAGENT_TASK_TIMEOUT_MS`) within one parent execution; parent-only tools wait for or cancel children. A zero-timeout wait polls status. See [subagent lifecycle](agent/docs/subagents.md).

Desktop selection prompts queue across sources and expire with their callers; dialog events carry the displayed source and request IDs. Tool permission approval and denial events carry the displayed request ID so stale clicks cannot resolve or dismiss a replacement prompt. Clipboard tools share [transaction ownership](desktopApp/docs/clipboard-transactions.md) across parent and child agents.

Desktop [local model profiles](native/docs/model-assets-and-context-budget.md#desktop-text-models) own download sets, RAM gates and model-specific prompt/output handling. The persisted Qwen3 4B selector resolves to Qwen3.5 4B without reusing the old GGUF.

Backend [personality settings](backend/docs/personality-settings.md) are separate from system prompts. Revisioned snapshots go to the current connected channel if it declares `prompt.personality.set`, otherwise to another declaring channel. Settings save independently of discovery; no known receiver means synchronization is skipped.

Backend [external memory](backend/docs/external-memory.md) exposes exact-fact forgetting and one-time confirmed bank deletion only on authenticated interactive owner turns when Hindsight is configured.

Backend [attachment contracts](backend/docs/attachments.md) carry owner-scoped file references through messages, tools and sessions. Public WebSocket attachments use opaque bucket keys stored in `object_key`, independently of internal file UUIDs. Import completed uploads before acceptance, revalidate input files under row locks in the acceptance transaction, resolve accepted retries before storage access, and snapshot reply descriptors during the final transaction for terminal delivery and replay.

- Read and maintain this file before changing the repository. Before editing a module, read its linked `AGENTS.md`, any applicable nested instructions, and relevant topics linked in its `Pain points` section.
- Keep runtime documentation and `docs/public-souz-contract/` concise and current-state only. Do not write change-history phrases such as “now we do”.
- Keep pre-implementation client agreements under `docs/proposals/`, explicitly marked as proposed and not implemented; exclude them from the implemented public API schemas.

Backend client commands share one live-only dispatcher, permit concurrent correlated calls, and have no durable result receipts or command replay.

## UI architecture principles

- UI layers (Screens and Composables) should not do neither business logic, nor IO operations.
- UI-logic should be coordinated from ViewModels. ViewModel may delegate business logic to UseCases.

File-backed Skill discovery reads existing storage without provisioning a user runtime. Stored-file reply selection uses owner-scoped storage metadata; workspace transfers and script execution retain LOCAL/DOCKER runtime boundaries.

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
