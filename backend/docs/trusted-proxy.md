# Trusted proxy

## Invariant

Every `/v1/**` request except `POST /v1/chats`, `GET /v1/chats/{chatId}/ws`, `GET /v1/ws`, and `GET /v1/chats/{chatId}/threads/{threadId}` derives identity only from the proxy-managed `X-Souz-Proxy-Auth` and `X-User-Id` headers. The proxy secret must match configured server state, and the opaque user ID must pass shape validation. Request identity middleware provisions the user before settings, provider keys, legacy chat reads, or other proxy services run.

The Client-Souz routes are credential-free inside a trusted network. HTTP and WebSocket chat creation accept a trusted UUID `userId`; each submitted device must repeat the same UUID. WebSocket and thread-status reads are scoped by the stored chat and matching `clientType`. Public root, health, readiness, and documentation routes also require no headers. Credential-free WebSocket executions cannot expose destructive memory tools; those require an authenticated interactive owner message. Hooks and scheduled executions also lack deletion authority.

`GET /health` checks process liveness. `GET /ready` returns an empty `200` when PostgreSQL reports `transaction_read_only = off`, or an empty `503` on read-only, connection failure, or timeout. The query has a two-second network timeout on its borrowed connection; Hikari resets it on return. Pool borrowing can exceed that deadline while validating stale connections, up to the pool's connection timeout. Configure deployment readiness probes on `/ready` with `timeoutSeconds: 3` and keep liveness probes on `/health`; `POSTGRES_DSN` needs no change. Probe SQL failures log only the exception class and SQLState, without driver messages containing host names.

## Why it is fragile

Allowing the Client-Souz body identity outside creation and execute device metadata creates an alternate authority path. These boundaries can still cross chat ownership if the stored chat user, frame chat ID, and device user are not compared before execution.

## Safe-change guidance

- Obtain proxy-route identity through the installed request-identity boundary.
- On the Client-Souz boundary, validate UUID shape, provision the create-chat `userId`, and require every `message.submit.payload.device.userId` to equal the chat owner.
- Apply ownership checks to every user resource, including nested chat, execution, option, event, Telegram, and provider-key operations.
- Pass `ToolInvocationMeta.userId` into backend runtime work. Backend sandbox scope is user-scoped and does not currently add conversation scope.
- Keep `BackendToolCapabilityPolicy` the only place that decides compiled backend tool exposure. It owns the backend-safe categories and execution-bound LLM tool names; bootstrap capabilities, `enabledTools` validation, and execution selection all read it. Exclude compiled web categories and search names from both execution and advertising.
- Persist `enabledTools` only as the user's own selection. A missing value follows every advertised tool, so storing the advertised set as a default hides every later tool from that user.
- Build execution catalog snapshots by restricting policy-hostable compiled tool-backed Skills with the trusted user's effective `enabledTools` snapshot, then merge bundled defaults, the owned chat's declarations, and its discovered client tools in that precedence order. Discovered tools retain their supplying channel; other client tools use the current client or an explicitly selected connected `channelId`. Use the user-scoped sandbox provider only for file-backed Skill discovery and execution.
- Keep backend Codex access server-managed. Its OAuth access token, refresh token, account ID, and expiry belong to one backend credential set stored in Postgres with application-layer encryption; do not treat the per-user provider-key `apiKey` field as a complete Codex session. Container deployments must persist Postgres data and keep the master key outside the database.
- Document both proxy headers as jointly required OpenAPI security schemes for protected `/v1` operations. Keep `POST /v1/chats` unsecured in OpenAPI and the WebSocket contract in `docs/public-souz-contract`.

## Verification

Run `./gradlew :backend:test`. Cover proxy rejection and provisioning, public chat UUID validation and idempotency, WebSocket client-type and device-owner checks, OpenAPI security exceptions, and sandbox/tool user scoping.
