# Public client WebSocket

Use the [public contract](../../docs/public-souz-contract/README.md) for schemas, errors, limits, and examples.

## Invariant

Public Client-Souz routes rely on a trusted network and upstream identity validation. Validate and provision the trusted UUID at chat creation. A multi-chat socket can serve multiple owners. Resolve each frame against its chat and matching client type. The submit device user must match the chat owner. Scope thread reads, subscriptions, catalogues, and tool result correlation to the resolved owner.

HTTP and WebSocket creation share `(user_id, request_id)` and atomically store the creation hash and declarations with the chat. Submit, history append, and cancel share `(chat_id, request_id)`. PostgreSQL serializes them under the chat row lock. Recheck the receipt under that lock before selecting a thread or applying an effect, and commit the receipt and effect together. Matching retries return the original ACK with only `duplicate` changed, even after completion. Reusing a key with a different operation or payload conflicts. Preserve the [payload hashes](../src/main/kotlin/ru/souz/backend/client/PublicPayloadHash.kt), including the client-supplied nullable thread ID, so persisted receipts remain valid. See [chat creation](../src/main/kotlin/ru/souz/backend/chat/service/ChatService.kt) and the [request repository](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresClientRequestRepository.kt).

See [thread selection](../../docs/public-souz-contract/README.md#commands) and the [distributed ownership rules](distributed-backend-boundary.md#invariant) for active threads, history append, and recovery.

## Why it is fragile

Socket writes, durable commits, mailbox delivery, and terminal state can race. A receipt must survive disconnect, while runtime work must observe committed input and tool callers must observe results only after the server writes their ACK.

## Safe-change guidance

- In [submit and cancel handling](../src/main/kotlin/ru/souz/backend/client/PublicClientService.kt), keep attachment preparation outside the transaction and after the receipt lookup. The application owns the non-cancellable handoff from initial acceptance through startup or durable failure finalization. Serialize follow-up acceptance with terminal persistence. Publish to the reserved mailbox only after commit, with preceding role-preserving history through the new input's trigger sequence. Persist cancellation before propagating it. See [attachments](attachments.md#websocket-transport) and [memory capture](external-memory.md).
- Keep ACK ordering local to the submitting connection. Write the ACK and any submit or cancel status feedback before forwarding events caused by the command. Other connections and reconnect replay proceed independently. Runtime commits and event publication must proceed without waiting for socket delivery. [Socket coordination](../src/main/kotlin/ru/souz/backend/http/routes/PublicClientConnection.kt)
- Keep one sender and durable cursor per subscribed chat. Register for live signals before querying the durable tail or replay. Catch up from the last covered sequence and suppress duplicates by sequence before forwarding live events. Prepare a submit's automatic subscription at the durable tail before accepting input. Prepare an explicit replay replacement before retiring the old sender. Join retired senders outside the writer lock before ACK. Disconnect releases subscription resources while runtime work and tool deadlines continue. [Replay implementation](../src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt) · [Subscription contract](../../docs/public-souz-contract/README.md#subscriptions-and-reconnect)
- Follow the [public delivery contract](../../docs/public-souz-contract/README.md#delivery-and-retries) for event selection and sequence gaps. Exclude legacy stored client tool starts from replay. Thread status reads durable state independently of replay.
- Keep client tool results process-local and without durable receipts. Reserve the first result before its deadline, then complete the waiter after a successful ACK write. Preparation or write failures must settle the waiter. Cross-channel dispatch requires an owned, unarchived device chat and a live subscription on the caller's process. Its correlation ID creates no target execution. Keep bounded command queues separate from droppable notifications and reject publication when no queue accepts the command. [Live dispatch](../src/main/kotlin/ru/souz/backend/client/LiveClientToolDispatcher.kt) · [Image preparation](images.md#client-tool-images) · [Deployment cutover](../../docs/public-souz-contract/README.md#deployment)
- Enforce the [client catalogue contract](../../docs/public-souz-contract/README.md#client-tool-catalogue) at lookup and invocation. [Channel persistence](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresChatRepository.kt) · [Catalogue merge](../src/main/kotlin/ru/souz/backend/client/UserClientTools.kt) · [Discovery and routing](../src/main/kotlin/ru/souz/backend/client/BackendClientSkills.kt)

## Diagnostics

Correlate socket ACK writes and event sends in `SouzClientWebSocket` with stored events in `AgentEventService`. An ACK log confirms the server write. Use coroutine-scoped MDC for the current command or event. Failures before chat resolution can be traced by `socketId`.

```logql
{app="souz-backend"} | json | mdc_chatId="..." | mdc_threadId="..."
```

Use the deployment's stream labels and substitute the chat and thread IDs. Log only bounded, sanitized envelope identifiers and structured decode diagnostics. Keep raw frames, prompts, titles, tool arguments and results, and raw decoder errors out of logs. [Logging context](../src/main/kotlin/ru/souz/backend/common/BackendLogging.kt)

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendPublic*E2eTest' --tests 'ru.souz.backend.storage.postgres.PostgresRepositoriesTest'` for runtime changes to these invariants. Cover cross-replica retries and routing, history ordering, submit/cancel races, ACK ordering, tool-result reservation, and reconnect replay.
