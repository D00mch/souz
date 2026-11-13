# Job ownership and recovery

## Ownership and fencing

Keep creation, listing, cancellation, and failure history owner-scoped. Start one sequential worker per host scope with an explicit handler. Await host capacity before claiming. Excluded owners' jobs stay pending without spending attempts. [Job API](../../job-api/src/main/kotlin/ru/souz/jobs/JobService.kt) · [Worker lifecycle](../src/main/kotlin/ru/souz/jobs/impl/PostgresJobService.kt)

Commit the claim before running the handler. PostgreSQL time governs eligibility and leases across replicas. Renewals and outcomes must check the running status, matching token, and unexpired lease in the same transaction after locking the row. [Claims, fencing, and recovery](../src/main/kotlin/ru/souz/jobs/impl/PostgresJobStore.kt)

Handlers have at-least-once delivery. Expired leases permit another attempt, so deduplicate external effects by `(jobId, scheduledAt)`. Keep that identity stable across retries and keep credentials outside payloads. Cancellation is cooperative. Keep the handler and heartbeat in one scope and join the heartbeat before writing the outcome. Treat handler cancellation as an attempt failure while the worker remains active. Propagate application cancellation so interrupted claims remain recoverable.

## Recovery and operations

Failed or abandoned claims spend an attempt. Exhaustion records durable occurrence failure history, including after the final lease expires. One-offs and recurring jobs fail on exhaustion by default. With `continueAfterFailure`, cron jobs record the failure and advance to the next future occurrence. Later successes preserve prior failures.

Join workers before closing the data source. Keep job migrations in a separate Flyway location and history table. Scrape the blocking queue gauge on an IO dispatcher. [Queue metric and replica aggregation](../../backend/docs/metrics.md#queries)

## Verification

For runtime changes, run `./gradlew :job-impl:test` with Docker. [Store tests](../src/test/kotlin/ru/souz/jobs/impl/PostgresJobStoreTest.kt) · [Worker tests](../src/test/kotlin/ru/souz/jobs/impl/JobWorkerTest.kt)
