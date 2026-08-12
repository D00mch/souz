# Provider HTTP lifecycle

## Invariants

- Remote provider adapters receive host-owned `HttpClient` instances. They do not create, derive, or close clients.
- Credentials, selected models, endpoints, sessions, and timeouts are request state and are applied explicitly to each request.
- Desktop may support Giga through its application-scoped transport and token state. Backend composition excludes Giga entirely.
- The host that creates a provider transport closes it exactly once after in-flight application work is stopped.

## Why this is fragile

Each Ktor client owns an engine, connection pool, plugins, and coroutine lifecycle. Constructing one in a provider adapter makes a cheap execution object retain expensive resources with no visible owner. Putting credentials or timeouts in shared client defaults also allows concurrent users and requests to affect each other. Giga token state is credential-specific and must never be stored in process-global system properties.

## Safe changes

- Add transport behavior to the smallest existing shared client profile that satisfies its TLS requirements; keep OpenAI and Giga trust profiles distinct.
- Set authorization and request timeout in the request builder, not in `defaultRequest` from mutable settings.
- Keep provider parsing and lightweight per-execution metadata in adapters; keep engines, TLS configuration, and plugins in host-owned resources.
- Use coroutine synchronization for token refresh and credential caches. Propagate cancellation and avoid JVM thread-local or monitor state.
- Keep token accounting at the `LLMChatAPI` boundary, where normalized usage is available, rather than in HTTP middleware or provider adapters.

## Verification

Run `./gradlew :sharedLogic:jvmTest`. Cover concurrent credential and timeout isolation, shared-client reuse, Giga token invalidation, and exactly-once host shutdown.
