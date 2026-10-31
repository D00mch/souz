# Backend

`:backend` is the headless JVM HTTP host. It exposes public health and readiness, generated API documentation, and the credential-free Client-Souz chat creation and WebSocket boundary. Other `/v1/**` operations stay behind the trusted proxy. Chat turns run through the shared `:agent` kernel.

Before changing this module, read the relevant pain-point topics below.

## Boundaries

- Treat proxy-provided identity as the authority for proxy routes. The public Client-Souz boundary accepts trusted UUID user identity in `POST /v1/chats`, `chat.create.payload.userId`, and `message.submit.payload.device.userId` and must keep those values equal for chat ownership.
- Expose only the tools `BackendToolCapabilityPolicy` allows. Change that policy, not its callers, when backend tool exposure changes.
- Build immutable catalog snapshots by applying each execution's enabled-tool snapshot to policy-hostable compiled tools before adding Client-Souz tool-backed Skills. The backend excludes compiled web tools; its `WEB_SEARCH` category contains only client `web.search`. Executions without a current client can target a connected client channel with `channelId`.
- Persist creation declarations atomically with the chat and retain their original idempotency hash. Always include bundled client Skills; declarations override matching defaults. Store one discovered catalogue separately per owned conversation, with precedence over declarations/defaults and binding to its supplying channel. Validate and persist a complete replacement before exposing it to the running execution. Exclude both catalogues from metadata reads. Only the parent discovers; children receive selected immutable snapshots behind `RunSkillCommand`.
- Public WebSocket messages import completed S3 uploads by opaque `objectKey` and assign independent internal UUIDs. Resolve accepted receipts before import; preserve structured files in initial and follow-up input. Persist `{objectKey, name, presentation}` in terminal and forwarded events so replay needs no storage access. File selection is transport-independent; destinations validate file support and ownership.
- Enrich declared known client tools with bundled `skills/client` instructions, categories, and deadlines. Preserve their exact names, persisted descriptions, and input schemas. Custom client tools use a one-minute deadline.
- Build every turn with the backend's single request-scoped steerable `AgentId.SKILLS_GRAPH`. Advertise only its fixed core Skill tools and discover catalog capabilities through Skill inventory.
- Reuse the application-owned Jev HTTP/1.1 client across users and executions; provider resources close it after application work stops. See [provider HTTP lifecycle](../sharedLogic/docs/provider-http-lifecycle.md).
- Keep product messages, thread lifecycle, agent continuation state, client tool calls, idempotency receipts, and replay events in their existing ownership layers.
- Telegram and VK bindings use encrypted tokens, private-account linking, and independent leased poll loops. Their shared poll scheduler keeps idle long polls outside the processing limit; channel providers use platform-specific formatting and shared chunk delivery bookkeeping.
- Public-client executions attempt one best-effort initial waiting phrase unless routed simple; narration, parent tool starts or completion can overtake it. Other assistant progress is opt-in through `narrateSteps`; live-only bot observers stop delivery before the final reply. The per-user `intermediateMessages` setting, on by default, suppresses both when off. See [execution events](docs/execution-openapi-and-events.md).
- PostgreSQL stores structured repositories and [conversation Knowledge](docs/conversation-knowledge.md). Sandbox workspaces remain filesystem-backed and user-scoped.
- Bind one `JobService` and `PostgresJobService` instance to the application datasource. `BackendRuntime.startBackgroundServices` starts the only job worker with the scheduled-task handler; shutdown joins application work before closing storage. See [job ownership](../job-impl/docs/job-ownership.md).
- Scheduled tasks are `JobService` jobs managed by the `SCHEDULING` tools. The agent chooses at-least-once or at-most-once delivery per task; occurrence IDs and an atomic execution insert enforce it, and the handler returns without awaiting the agent. Admission limits each process to one active execution per owner and a configurable total; capacity remains occupied through cancellation cleanup. Dispatch reserves a durable per-user UTC daily quota with execution insertion and checks delivery permissions. See [scheduled tasks](docs/scheduled-tasks.md).
- Workspace hooks authenticate before agent setup and persist receipts before acknowledgement. Each new receipt owns a separate hidden technical chat; duplicate deliveries reuse the receipt. Hook recovery is single-process and only touches receipt-linked executions; see [the hook contract](../docs/hooks.md).
- Hook intake capacity is isolated per configured owner and survives reload. Verifier commands use the trusted owner's configured LOCAL/DOCKER sandbox through `SandboxCommandExecutor`, before Skill discovery or LLM use. They share that sandbox's permissions and do not create a separate verification container.
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep the WebSocket routes out of the generated document and maintain its schema in `docs/public-souz-contract`.
- Backend owns execution, capacity, LLM, and tool metrics; `:job-impl` owns queue metrics and binds them to the shared registry at composition. Restrict `/metrics` at ingress; its [metric reference](docs/metrics.md) defines accounting and replica semantics.

## Pain points

- [Personality settings](docs/personality-settings.md) — validated user style, conversational scope and Orion snapshots.
- [User file storage](docs/s3-storage.md) — private S3 bucket, owner-scoped metadata, expiry and retryable cleanup.
- [Attachment contracts](docs/attachments.md) — message references, session persistence, sandbox preparation, and explicit reply selection.
- [Named LLM connections](docs/named-llm-connections.md) — server model names, independent endpoints and keys, subagents and summarization.
- [Metrics accounting](docs/metrics-accounting.md) — commit boundaries, bounded labels, waiting time and replica ownership; see the [metric reference](docs/metrics.md).
- [Workspace hooks](docs/hooks.md) — trusted ownership, admission, persistent call budgets, sequential dispatch and single-process recovery.
- [Trusted proxy](docs/trusted-proxy.md) — identity validation, provisioning, user scoping, and backend-safe tools.
- [Execution, OpenAPI, and events](docs/execution-openapi-and-events.md) — runtime ownership, event durability, compatibility, and route documentation.
- [Public client WebSocket](docs/public-client-websocket.md) — public idempotency, thread/runtime coordination, client tools, acknowledgement ordering, and replay.
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

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
