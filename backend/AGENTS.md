# Backend

`:backend` is the headless JVM HTTP host. It exposes public health, generated API documentation, and the credential-free Client-Souz chat creation and WebSocket boundary. Other `/v1/**` operations stay behind the trusted proxy. Chat turns run through the shared `:agent` kernel without Compose or desktop-only services.

Before changing this module, read the relevant pain-point topics below.

## Boundaries

- Treat proxy-provided identity as the authority for proxy routes. The public Client-Souz boundary accepts trusted UUID user identity in `POST /v1/chats`, `chat.create.payload.userId`, and `message.submit.payload.device.userId` and must keep those values equal for chat ownership.
- Expose only the tools `BackendToolCapabilityPolicy` allows; desktop integrations and UI dependencies must stay outside this module. Change that policy, not its callers, when backend tool exposure changes.
- Build one immutable request-scoped catalog by applying each execution's enabled-tool snapshot to the policy-hostable compiled tools before adding Client-Souz tool-backed Skills. The backend excludes compiled web tools; its `WEB_SEARCH` category contains only client `web.search`. Executions without a current client can target a connected client channel with `channelId`.
- Build every turn with the backend's single request-scoped steerable `AgentId.SKILLS_GRAPH`. Advertise only its fixed core Skill tools and discover catalog capabilities through Skill inventory.
- Reuse the application-owned Jev HTTP/1.1 client across users and executions; provider resources close it after application work stops. See [provider HTTP lifecycle](../sharedLogic/docs/provider-http-lifecycle.md).
- Keep product messages, thread lifecycle, agent continuation state, client tool calls, idempotency receipts, and replay events in their existing ownership layers.
- Telegram and VK bindings use encrypted tokens, private-account linking, and independent leased poll loops. Their shared poll scheduler keeps idle long polls outside the processing limit; channel providers use platform-specific formatting and shared chunk delivery bookkeeping.
- Assistant progress is opt-in through `narrateSteps`; live-only bot observers stop delivery before the final reply. See [execution events](docs/execution-openapi-and-events.md).
- PostgreSQL stores structured repositories and [conversation Knowledge](docs/conversation-knowledge.md). Sandbox workspaces remain filesystem-backed and user-scoped.
- Bind one `JobService` and `PostgresJobService` instance to the application datasource. Job workers start only through `BackendRuntime.startJobWorker` with a host-supplied handler; shutdown joins application work before closing storage. See [job ownership](../job-impl/docs/job-ownership.md).
- Workspace hooks authenticate before agent setup and persist receipts before acknowledgement. Each new receipt owns a separate hidden technical chat; duplicate deliveries reuse the receipt. Hook recovery is single-process and only touches receipt-linked executions; see [the hook contract](../docs/hooks.md).
- Hook intake capacity is isolated per configured owner and survives reload. Verifier commands use the trusted owner's configured LOCAL/DOCKER sandbox through `SandboxCommandExecutor`, before Skill discovery or LLM use. They share that sandbox's permissions and do not create a separate verification container.
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep the WebSocket routes out of the generated document and maintain its schema in `docs/public-souz-contract`.
- Keep Prometheus instrumentation in `:backend`, using repository commits and existing LLM/tool hooks. Restrict `/metrics` at ingress; its [metric reference](docs/metrics.md) defines accounting and replica semantics.

## Pain points

- [Metrics accounting](docs/metrics-accounting.md) — commit boundaries, bounded labels, waiting time and replica ownership.
- [Workspace hooks](docs/hooks.md) — trusted ownership, admission, persistent call budgets, sequential dispatch and single-process recovery.
- [Trusted proxy](docs/trusted-proxy.md) — identity validation, provisioning, user scoping, and backend-safe tools.
- [Execution, OpenAPI, and events](docs/execution-openapi-and-events.md) — runtime ownership, event durability, compatibility, and route documentation.
- [Public client WebSocket](docs/public-client-websocket.md) — public idempotency, thread/runtime coordination, client tools, acknowledgement ordering, and replay.
- [Distributed backend boundary](docs/distributed-backend-boundary.md) — which backend entry points have distributed runtime ownership and recovery.
- [Job ownership and recovery](../job-impl/docs/job-ownership.md) — explicitly started workers, PostgreSQL claims, and at-least-once handlers.
- [Conversation Knowledge](docs/conversation-knowledge.md) — durable tool/subagent results, ownership, deletion, and read-only deployment verification.
- [Telegram bindings](docs/telegram-bindings.md) — token custody, private-account linking, polling leases, and checkpoint safety.
- [VK bindings](docs/vk-bindings.md) — private-account linking, encrypted tokens, Long Poll cursors, and lease fencing.
- [Testing](docs/testing.md) — production-wired E2E coverage, Docker requirement, and allowed test doubles.
- [Container builds](docs/container-builds.md) — JVM temporary files and Kaniko snapshots.
- [External memory](docs/external-memory.md) — Hindsight owner isolation, scope tags, grounded capture, and safe mutation boundaries.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
