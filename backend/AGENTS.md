# Backend

`:backend` is the headless JVM HTTP host. It exposes public health, generated API documentation, and the credential-free Client-Souz chat creation and WebSocket boundary. Other `/v1/**` operations stay behind the trusted proxy. Chat turns run through the shared `:agent` kernel without Compose or desktop-only services.

Before changing this module, read the [pain-point index](docs/pain-points.md) and the topics relevant to the area.

## Boundaries

- Treat proxy-provided identity as the authority for proxy routes. The public Client-Souz boundary accepts trusted UUID user identity only in `POST /v1/chats` and `message.submit.payload.device` and must keep those values equal for chat ownership.
- Expose only backend-safe runtime tools; desktop integrations and UI dependencies must stay outside this module.
- Build one immutable request-scoped catalog by applying each execution's enabled-tool snapshot to compiled tools before adding Client-Souz tool-backed Skills.
- Build every turn with the backend's single request-scoped steerable `AgentId.SKILLS_GRAPH`. Advertise only its fixed core Skill tools and discover catalog capabilities through Skill inventory.
- Keep product messages, thread lifecycle, agent continuation state, client tool calls, idempotency receipts, and replay events in their existing ownership layers.
- PostgreSQL is the structured repository store. Sandbox workspaces remain filesystem-backed and user-scoped.
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep the WebSocket route out of the generated document and maintain its schema in `docs/public-souz-contract`.

## Verification

- Run backend tests with Docker running: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
