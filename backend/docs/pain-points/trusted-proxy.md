# Trusted proxy

## Invariant

Every `/v1/**` request derives identity only from the proxy-managed `X-Souz-Proxy-Auth` and `X-User-Id` headers. The proxy secret must match configured server state, and the opaque user ID must pass shape validation. Request identity middleware provisions the user before settings, chats, provider keys, executions, or other user services run.

Public root, health, and documentation routes do not require these headers. Their public status must not create an alternate path into `/v1` services.

## Why it is fragile

Accepting identity from a body, query, route parameter, or unverified header lets a caller cross tenant boundaries. A correctly authenticated route can still leak data if repositories, runtime tools, skill storage, or sandbox resolution omit the trusted user ID.

## Safe-change guidance

- Obtain user identity through the installed request-identity boundary; never accept a user ID as authority from request payloads.
- Apply ownership checks to every user resource, including nested chat, execution, option, event, Telegram, and provider-key operations.
- Pass `ToolInvocationMeta.userId` into backend runtime work. Backend sandbox scope is user-scoped and does not currently add conversation scope.
- Keep the backend tool catalog limited to backend-safe categories and exclude desktop-only tools and `WebImageSearch`.
- Restrict compiled tools with the trusted user's effective `enabledTools` snapshot for discovery and invocation without hiding core or user-installed file-backed skills.
- Document both proxy headers as jointly required OpenAPI security schemes for every `/v1` operation.

## Verification

Run `./gradlew :backend:test`. Cover missing server configuration, invalid proxy credentials, invalid/missing user identity, provisioning, cross-user access rejection, OpenAPI security, and sandbox/tool user scoping.
