# Trusted proxy

## Invariant

Every `/v1/**` request except `POST /v1/chats`, `GET /v1/chats/{chatId}/ws`, and `GET /v1/chats/{chatId}/threads/{threadId}` derives identity only from the proxy-managed `X-Souz-Proxy-Auth` and `X-User-Id` headers. The proxy secret must match configured server state, and the opaque user ID must pass shape validation. Request identity middleware provisions the user before settings, provider keys, legacy chat reads, or other proxy services run.

The Client-Souz routes are credential-free inside a trusted network. Chat creation accepts a trusted UUID `userId`; each submitted device must repeat the same UUID. WebSocket and thread-status reads are scoped by the stored chat and matching `clientType`. Public root, health, and documentation routes also require no headers.

## Why it is fragile

Allowing the Client-Souz body identity outside its two explicit boundary operations creates an alternate authority path. Either boundary can still cross chat ownership if the stored chat user, frame chat ID, and device user are not compared before execution.

## Safe-change guidance

- Obtain proxy-route identity through the installed request-identity boundary.
- On the Client-Souz boundary, validate UUID shape, provision the create-chat `userId`, and require every `message.submit.payload.device.userId` to equal the chat owner.
- Apply ownership checks to every user resource, including nested chat, execution, option, event, Telegram, and provider-key operations.
- Pass `ToolInvocationMeta.userId` into backend runtime work. Backend sandbox scope is user-scoped and does not currently add conversation scope.
- Keep the backend tool catalog limited to backend-safe categories and exclude desktop-only tools and `WebImageSearch`.
- Restrict compiled tools with the trusted user's effective `enabledTools` snapshot for discovery and invocation without hiding core or user-installed file-backed skills.
- Document both proxy headers as jointly required OpenAPI security schemes for protected `/v1` operations. Keep `POST /v1/chats` unsecured in OpenAPI and the WebSocket contract in `docs/public-souz-contract`.

## Verification

Run `./gradlew :backend:test`. Cover proxy rejection and provisioning, public chat UUID validation and idempotency, WebSocket client-type and device-owner checks, OpenAPI security exceptions, and sandbox/tool user scoping.
