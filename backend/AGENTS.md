# Backend

`:backend` is the headless JVM HTTP host. It exposes public health, generated API documentation, and the credential-free Client-Souz chat creation and WebSocket boundary. Other `/v1/**` operations stay behind the trusted proxy. Chat turns run through the shared `:agent` kernel without Compose or desktop-only services.

Before changing this module, read the [pain-point index](docs/pain-points.md) and the topics relevant to the area.

## Boundaries

- Treat proxy-provided identity as the authority for proxy routes. The public Client-Souz boundary accepts trusted UUID user identity in `POST /v1/chats`, `chat.create.payload.userId`, and `message.submit.payload.device.userId` and must keep those values equal for chat ownership.
- Expose only the tools `BackendToolCapabilityPolicy` allows; desktop integrations and UI dependencies must stay outside this module. Change that policy, not its callers, when backend tool exposure changes.
- Build one immutable request-scoped catalog by applying each execution's enabled-tool snapshot to the policy-hostable compiled tools before adding Client-Souz tool-backed Skills. Client-Souz executions replace compiled `InternetSearch` with client `web.search`; other executions retain server search and can target client Skills with `channelId`.
- Build every turn with the backend's single request-scoped steerable `AgentId.SKILLS_GRAPH`. Advertise only its fixed core Skill tools and discover catalog capabilities through Skill inventory.
- Keep product messages, thread lifecycle, agent continuation state, client tool calls, idempotency receipts, and replay events in their existing ownership layers.
- Telegram and VK bindings use encrypted tokens, private-account linking, and independent leased poll loops. Their shared poll scheduler keeps idle long polls outside the processing limit; channel providers use platform-specific formatting and shared chunk delivery bookkeeping.
- Assistant progress is opt-in through `narrateSteps`; live-only bot observers stop delivery before the final reply. See [execution events](docs/pain-points/execution-openapi-and-events.md).
- PostgreSQL stores structured repositories and [conversation Knowledge](docs/pain-points/conversation-knowledge.md). Sandbox workspaces remain filesystem-backed and user-scoped.
- Workspace hooks authenticate before agent setup and persist receipts before acknowledgement. Each new receipt owns a separate hidden technical chat; duplicate deliveries reuse the receipt. Hook recovery is single-process and only touches receipt-linked executions; see [the hook contract](../docs/hooks.md).
- Hook intake capacity is isolated per configured owner and survives reload. Verifier commands use the trusted owner's configured LOCAL/DOCKER sandbox through `SandboxCommandExecutor`, before Skill discovery or LLM use. They share that sandbox's permissions and do not create a separate verification container.
- Scheduled-task tools are compiled Kotlin operations, filtered by `enabledTools`. Orion owns titles and schedules; ordinary hook manifests remain unchanged. Generated prompts preserve the originating user channel as a default delivery destination across edits, independently of scheduler RPC routing. Use the live-only client dispatcher for internal scheduler RPC, including same-channel calls, so Bearer credentials never enter durable events/tool audit. See [scheduled tasks](../docs/scheduled-tasks.md).
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep the WebSocket routes out of the generated document and maintain its schema in `docs/public-souz-contract`.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
