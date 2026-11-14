# Trusted proxy

## Invariant

Protected `/v1/` routes derive identity from the proxy-managed `X-Souz-Proxy-Auth` and `X-User-Id` headers. The [request identity plugin](../src/main/kotlin/ru/souz/backend/security/RequestIdentity.kt) checks the configured proxy secret, validates the opaque user ID, and provisions the user before route logic runs. Public root, health, readiness, and documentation routes require no headers.

When either proxy header is supplied on a Client-Souz route, verify both. Account personality initialization requires verified identity matching the creation owner. Credential-free turns cannot access owner MCP settings or JVM plugins; destructive memory tools require authenticated interactive owner authority, which hooks and scheduled jobs also lack. [Personality initialization](personality-settings.md#ownership-and-writes) · [MCP boundary](mcp.md) · [Plugin boundary](jvm-plugins.md) · [Memory authority](external-memory.md#forgetting)

## Why it is fragile

Body-provided identities create an alternate authority path on protected routes. Keep their use inside the [public client identity boundary](public-client-websocket.md#invariant).

## Safe-change guidance

- Apply trusted ownership checks to every user resource, including nested resources. Scope sandbox access and file-backed Skill discovery and execution by `ToolInvocationMeta.userId` alone.
- Bound settings PATCH and onboarding request bodies, including chunked input. Bound explicit `enabledTools` counts and UTF-8 bytes, including unavailable plugin names, before saving or creating execution metadata. Repair oversized stored selections with a bounded settings PATCH.
- Follow the [backend catalogue rules](../../sharedLogic/docs/tool-catalog-composition.md#backend-composition) when exposing or saving tool selections.
- Keep Codex credentials server-managed and encrypted in PostgreSQL as one set. The per-user provider-key endpoint rejects `codex`. Persist PostgreSQL data and keep the master key outside the database. See the [deployment boundary](distributed-backend-boundary.md#invariant) for refresh coordination.
- Document both proxy headers as jointly required OpenAPI security schemes for protected operations. Keep `POST /v1/chats` unsecured in OpenAPI and maintain WebSocket schemas in the public contract.

Use `/health` for process liveness and `/ready` for writable PostgreSQL readiness. Read-only state, connection failure and timeout make readiness unavailable. Bound probe SQL and keep driver messages and host names out of diagnostics. Pool borrowing may exceed the query deadline; configure deployment readiness probes with `timeoutSeconds: 3`. [Probe implementation](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresReadinessProbe.kt)

## Verification

Run `./gradlew :backend:test` with Docker. See [request identity](../src/test/kotlin/ru/souz/backend/security/RequestIdentityTest.kt), [resource ownership](../src/test/kotlin/ru/souz/backend/e2e/BackendOwnershipE2eTest.kt), and [OpenAPI security](../src/test/kotlin/ru/souz/backend/e2e/BackendCompositionE2eTest.kt) tests. Follow [public WebSocket verification](public-client-websocket.md#verification) for public identity and routing.
