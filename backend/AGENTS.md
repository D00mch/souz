# Backend

`:backend` is the headless JVM HTTP host. It exposes public health and generated API documentation, protects `/v1/**` behind a trusted proxy, and runs chat turns through the shared `:agent` kernel without Compose or desktop-only services.

Before changing this module, read the [pain-point index](docs/pain-points.md) and the topics relevant to the area.

## Boundaries

- Treat proxy-provided identity as the only user identity and scope every user-owned operation accordingly.
- Expose only backend-safe runtime tools; desktop integrations and UI dependencies must stay outside this module.
- Apply each execution's immutable enabled-tool snapshot through request-scoped filters; keep core skill tools and user-scoped file-backed skills outside compiled-tool filtering.
- Select the agent for new conversations from backend configuration and retain the agent stored in existing conversation state.
- Keep product messages, execution lifecycle, agent continuation state, and durable/live events in their existing ownership layers.
- PostgreSQL is the structured repository store. User skill bundles and sandbox workspaces remain filesystem-backed and user-scoped.
- Give each ordinary HTTP route explicit OpenAPI metadata. Keep WebSocket routes out of the generated document.

## Verification

- Run backend tests: `./gradlew :backend:test`
- Run the server: `./gradlew :backend:run`
