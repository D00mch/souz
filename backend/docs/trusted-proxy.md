# Trusted proxy

## Invariant

Protected `/v1/` routes derive identity from the proxy-managed `X-Souz-Proxy-Auth` and `X-User-Id` headers. The [request identity plugin](../src/main/kotlin/ru/souz/backend/security/RequestIdentity.kt) checks the configured proxy secret, validates the opaque user ID, and provisions the user before route logic runs. Public root, health, readiness, and documentation routes require no headers.

The [Client-Souz routes](../../docs/public-souz-contract/README.md) are credential-free inside a trusted network. Validate and provision the trusted UUID at chat creation. Scope frames and thread-status reads to the stored chat and matching `clientType`, and require each submit's device UUID to match the chat owner.

## Why it is fragile

Allowing the Client-Souz body identity outside creation and execute device metadata creates an alternate authority path. These boundaries can still cross chat ownership if the stored chat user, frame chat ID, and device user are not compared before execution.

## Safe-change guidance

- Apply trusted ownership checks to every user resource, including nested resources. Scope sandbox access and file-backed Skill discovery and execution by `ToolInvocationMeta.userId` alone.
- Follow the [backend catalogue boundary](../AGENTS.md#boundaries) and [client catalogue rules](public-client-websocket.md#safe-change-guidance). Keep compiled web and search tools out of backend exposure. Persist explicit `enabledTools` selections only. An absent selection follows the advertised set as it grows.
- Keep Codex credentials server-managed and encrypted in PostgreSQL as one set. Persist PostgreSQL data and keep the master key outside the database. See the [deployment boundary](distributed-backend-boundary.md#invariant) for refresh coordination.
- Document both proxy headers as jointly required OpenAPI security schemes for protected operations. Keep `POST /v1/chats` unsecured in OpenAPI and maintain WebSocket schemas in the public contract.

## Verification

Run `./gradlew :backend:test` with Docker. See [request identity](../src/test/kotlin/ru/souz/backend/security/RequestIdentityTest.kt), [resource ownership](../src/test/kotlin/ru/souz/backend/e2e/BackendOwnershipE2eTest.kt), and [OpenAPI security](../src/test/kotlin/ru/souz/backend/e2e/BackendCompositionE2eTest.kt) tests. Follow [public WebSocket verification](public-client-websocket.md#verification) for public identity and routing.
