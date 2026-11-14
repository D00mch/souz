# Backend

`:backend` is the headless JVM HTTP host. Keep Compose, desktop integrations and UI dependencies outside this module. It owns product messages, execution lifecycle, durable receipts and replay, and persistence for the shared `:agent` kernel.

## Boundaries

- Apply the [trusted proxy](docs/trusted-proxy.md#invariant) and [public socket](docs/public-client-websocket.md#invariant) identity rules at their respective boundaries.
- Keep product messages and agent continuation state separate. Persist structured data and conversation Knowledge in PostgreSQL. Keep sandbox workspaces filesystem-backed and user-scoped.
- Follow the [backend catalogue rules](../sharedLogic/docs/tool-catalog-composition.md#backend-composition) for tool advertising, `enabledTools` validation, and execution selection.
- Use the [agent execution kernel](../agent/docs/execution-lifecycle.md#invariant) for conversation turns.
- Keep provider transports, storage and background services application-owned. Join application work and drain plugins before closing transports, local runtime and storage. Keep shared services open beneath plugin work that cannot drain. [Shutdown](src/main/kotlin/ru/souz/backend/app/BackendRuntimeResources.kt)
- Follow the [OpenAPI route rules](docs/execution-openapi-and-events.md#openapi) and [metrics ingress policy](docs/metrics.md).

## Pain points

- [MCP servers](docs/mcp.md) — owner settings, live discovery, network policy, budgets and execution cleanup.
- [JVM plugins](docs/jvm-plugins.md) — trusted in-process code, dedicated admin authorization, durable pins and fenced invocation leases. Tool exposure follows `enabledTools`.
- [Personality settings](docs/personality-settings.md) — revisioned user style, per-chat activation words and live Orion sync.
- [User file storage](docs/s3-storage.md) — private S3 bucket, owner-scoped metadata, expiry and retryable cleanup.
- [Attachment contracts](docs/attachments.md) — owner-bound references, acceptance and replay, Docker-only workspace transfers and stored-file selection.
- [Images](docs/images.md) — request-local vision, shared generation/editing routes, paid-operation receipts and client screenshots.
- [Named LLM connections](docs/named-llm-connections.md) — server model names, independent endpoints and keys, subagents and summarization.
- [Provider HTTP lifecycle](../sharedLogic/docs/provider-http-lifecycle.md) — process-owned transports, including Jev, and request-local credentials.
- [Metrics accounting](docs/metrics-accounting.md) — commit boundaries and instrumentation. See the [metric reference](docs/metrics.md) for meter ownership, labels, and aggregation.
- [Workspace hooks](docs/hooks.md) — trusted ownership, admission, persistent call budgets, sequential dispatch and single-process recovery.
- [Trusted proxy](docs/trusted-proxy.md) — identity validation, provisioning, user scoping, and backend-safe tools.
- [Execution, OpenAPI, and events](docs/execution-openapi-and-events.md) — lifecycle, durable events, live waiting and narration, compatibility, and route metadata.
- [Public client WebSocket](docs/public-client-websocket.md) — client catalogue discovery and routing, live command ACK ordering, durable receipts and replay.
- [Runtime sandbox and Skills](../sharedLogic/docs/runtime-sandbox-and-skills.md#invariants) — read-only file-backed Skill inventory and sandbox restrictions.
- [Distributed backend boundary](docs/distributed-backend-boundary.md) — which backend entry points have distributed runtime ownership and recovery.
- [Job ownership and recovery](../job-impl/docs/job-ownership.md) — explicitly started workers, PostgreSQL claims, and at-least-once handlers.
- [Scheduled tasks](docs/scheduled-tasks.md) — hidden occurrence chats, delivery semantics, non-blocking handler, budgets, and failure handling.
- [Conversation Knowledge](docs/conversation-knowledge.md) — durable tool/subagent results, ownership, deletion, and read-only deployment verification.
- [Telegram bindings](docs/telegram-bindings.md) — token custody, private-account linking, polling leases, and checkpoint safety.
- [VK bindings](docs/vk-bindings.md) — private-account linking, encrypted tokens, Long Poll cursors, and lease fencing.
- [Testing](docs/testing.md) — production-wired E2E coverage, Docker requirement, and allowed test doubles.
- [Model scenario tests](docs/agent-scenario-tests.md) — opt-in model evaluation against the full production catalog with mocked execution and scenario fixtures.
- [Container builds](docs/container-builds.md) — JVM temporary files and Kaniko snapshots.
- [External memory](docs/external-memory.md) — Hindsight owner isolation, scope tags, grounded capture, and safe mutation boundaries.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
