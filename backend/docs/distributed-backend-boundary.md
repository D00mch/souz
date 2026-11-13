# Distributed backend boundary

## Invariant

[PostgreSQL enforces one active execution per chat](../src/main/resources/db/migration/V4__stage12_option_rename.sql). Only the [Client-Souz public WebSocket thread path](public-client-websocket.md#invariant) has distributed-safe execution ownership. Route active-thread submits, tool results, and cancellation to the runtime owner. A local runtime miss on a still-active thread means unavailable. The owner renews its durable lease and holds live waiters in its process. Cross-channel tool callers and target subscriptions must share that process. Threadless history append can commit on any replica without consulting the live registry.

Run [thread recovery](../src/main/kotlin/ru/souz/backend/client/ClientThreadRecoveryService.kt) before accepting connections and periodically thereafter. Fail expired public leases and persist any missing terminal `thread.failed` events.

Ordinary trusted-proxy HTTP executions, Telegram/VK-triggered executions and [scheduled-task occurrences](scheduled-tasks.md) have process-local runtimes without renewable execution leases. [Job claims](../../job-impl/docs/job-ownership.md), [hook receipts](hooks.md), and [JVM plugin leases and pins](jvm-plugins.md) protect only the jobs, receipts, and invocations they own.

Server-managed Codex OAuth is safe for a single backend process through process-local refresh coordination. A terminal refresh rejection suppresses deployment credentials only while the configured refresh token matches the rejected value. A different `CODEX_REFRESH_TOKEN` lifts suppression, so replace all four deployment values together before restarting. Multi-replica deployments must not enable it unless refresh is database-coordinated and replaces the access token, refresh token, account ID, and expiry as one credential set.

## Why it is fragile

If a process-local ordinary execution is stranded in `queued`, `running`, or `cancelling`, later chat turns can be rejected as active-execution conflicts even though no runtime job exists. Treating all active statuses as crashed work is also unsafe because `waiting_option` intentionally outlives the runtime job while waiting for user input.

## Safe-change guidance

- Keep `waiting_option` separate from owned runtime work. Clear any runtime lease when entering `waiting_option`, and acquire a fresh lease when an option continuation resumes.
- Use a shared ownership model when extending recovery. In multi-replica deployments, fail an ordinary execution on startup only when ownership proves it was abandoned.
- Coordinate multi-replica Codex refresh with a database-backed lock or compare-and-set that rereads credentials before refresh and stores the rotated set atomically.

## Verification

Run `./gradlew :backend:test` with Docker. Use the [public WebSocket tests](../src/test/kotlin/ru/souz/backend/e2e/BackendPublicWebSocketE2eTest.kt) and [finalization tests](../src/test/kotlin/ru/souz/backend/e2e/BackendExecutionFinalizationE2eTest.kt) for owner routing, expired leases, cancellation races and option resume. Changes to ordinary recovery must cover HTTP and bot-triggered crashes. Verify concurrent refresh before deploying server-managed Codex across replicas.
