# Public client WebSocket

Use the [public contract](../../docs/public-souz-contract/README.md) for schemas, errors, limits, and examples.

## Invariant

Public Client-Souz routes rely on a trusted network and upstream identity validation. Validate and provision the trusted UUID at chat creation. A multi-chat socket can serve multiple owners. Resolve each frame against its chat and matching client type. The submit device user must match the chat owner. Scope thread reads, subscriptions, catalogues, and tool result correlation to the resolved owner.

Serialize creation by owner and request ID. For submit, history, and cancel, recheck the receipt under the chat lock before selecting a thread or mutating state. Commit each receipt with its effect. Preserve canonical hashes, including the caller's nullable thread ID, and build creation ACKs from persisted data so retries differ only in `duplicate`. [Idempotency scopes](../../docs/public-souz-contract/README.md#delivery-and-retries) · [Payload hashing](../src/main/kotlin/ru/souz/backend/client/PublicPayloadHash.kt)

See [thread selection](../../docs/public-souz-contract/README.md#commands) and the [distributed ownership rules](distributed-backend-boundary.md#invariant) for active threads, history append, and recovery.

## Safe-change guidance

- The application owns initial acceptance through startup or durable failure, even if the socket disconnects. Serialize follow-up commits with terminal persistence and publish through the reserved mailbox after commit. History-only appends never consult or mutate live state. Keep socket and runtime waits outside database transactions. Persist cancellation before propagating it. [Submit and cancel](../src/main/kotlin/ru/souz/backend/client/PublicClientService.kt) · [Attachment acceptance](attachments.md#websocket-transport)
- Keep ACK ordering local to the submitting connection. Write the ACK and any submit or cancel status feedback before forwarding events caused by the command. Other connections and reconnect replay proceed independently. Runtime commits and event publication must proceed without waiting for socket delivery. [Socket coordination](../src/main/kotlin/ru/souz/backend/http/routes/PublicClientConnection.kt)
- Subscribe before querying durable events and deduplicate catch-up by sequence. Prepare a submit's stream before accepting input, and prepare replay replacements before retiring the existing sender. Join retired senders before ACK, outside the writer lock. [Replay](../src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt) · [Subscription contract](../../docs/public-souz-contract/README.md#subscriptions-and-reconnect)
- Follow the [public delivery contract](../../docs/public-souz-contract/README.md#delivery-and-retries) for event selection and sequence gaps. Exclude legacy stored client tool starts from replay. Thread status reads durable state independently of replay.
- Reserve the first tool result before its deadline and resume the waiter only after its ACK write. Settle the waiter on preparation failure, failed write, or cancellation. Keep commands in bounded queues that never evict accepted commands. Reject publication if no subscription accepts it. [Live dispatch](../src/main/kotlin/ru/souz/backend/client/LiveClientToolDispatcher.kt) · [Image preparation](images.md#client-tool-images)
- Enforce the [client catalogue contract](../../docs/public-souz-contract/README.md#client-tool-catalogue) at lookup and invocation. Enumerate destinations through their owning providers without chat-history paging. [Discovery and routing](../src/main/kotlin/ru/souz/backend/client/BackendClientSkills.kt)

## Diagnostics

Correlate the server's ACK writes and stored events with coroutine-scoped MDC. Before chat resolution, use `socketId`.

```logql
{app="souz-backend"} | json | mdc_chatId="..." | mdc_threadId="..."
```

Use the deployment's stream labels and substitute the chat and thread IDs. Log only bounded, sanitized envelope identifiers and structured decode diagnostics. Keep raw frames, prompts, titles, tool arguments and results, and raw decoder errors out of logs. [Logging context](../src/main/kotlin/ru/souz/backend/common/BackendLogging.kt)

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendPublic*E2eTest' --tests 'ru.souz.backend.storage.postgres.PostgresRepositoriesTest'` for runtime changes to these invariants. Cover cross-replica retries and routing, history ordering, submit/cancel races, ACK ordering, tool-result reservation, and reconnect replay.
