# Backend

`:backend` is the headless JVM HTTP host. It exposes public health, generated API documentation, and the credential-free Client-Souz chat creation and WebSocket boundary. Other `/v1/**` operations stay behind the trusted proxy. Chat turns run through the shared `:agent` kernel without Compose or desktop-only services.

Before changing this module, read the [pain-point index](docs/pain-points.md) and the topics relevant to the area.

## Boundaries

- Treat proxy-provided identity as the authority for proxy routes. The public Client-Souz boundary accepts trusted UUID user identity in `POST /v1/chats`, `chat.create.payload.userId`, and `message.submit.payload.device.userId` and must keep those values equal for chat ownership.
- Expose only the tools `BackendToolCapabilityPolicy` allows; desktop integrations and UI dependencies must stay outside this module. Change that policy, not its callers, when backend tool exposure changes.
- Build one immutable request-scoped catalog by applying each execution's enabled-tool snapshot to the policy-hostable compiled tools before adding Client-Souz tool-backed Skills. Client-Souz executions replace compiled `InternetSearch` with client `web.search`; other executions retain server search and can target client Skills with `channelId`.
- Build every turn with the backend's single request-scoped steerable `AgentId.SKILLS_GRAPH`. Advertise only its fixed core Skill tools and discover catalog capabilities through Skill inventory.
- Keep product messages, thread lifecycle, agent continuation state, client tool calls, idempotency receipts, and replay events in their existing ownership layers.
- Telegram and VK bindings use encrypted tokens, private-account linking, and independent leased poll loops. Their shared poll scheduler keeps idle long polls outside the processing limit; channel providers share text splitting and delivery persistence.
- PostgreSQL stores structured repositories and [conversation Knowledge](docs/pain-points/conversation-knowledge.md). Sandbox workspaces remain filesystem-backed and user-scoped.
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep the WebSocket routes out of the generated document and maintain its schema in `docs/public-souz-contract`.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
