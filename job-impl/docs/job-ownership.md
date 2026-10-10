# Job ownership and recovery

## Invariant

Claim the earliest eligible job atomically with `FOR UPDATE SKIP LOCKED` and commit before running its handler. PostgreSQL time governs eligibility and lease validity. Renewals and outcome writes lock the row before validating the matching token, running status, and lease against current database time in the same transaction.

Recover expired running leases before claiming: requeue attempts below the limit with their existing availability time, and fail exhausted attempts. Both eligibility scans use `statement_timestamp()` as an index range boundary; the ordered claim reads only pending rows. Recovery skips locked rows. Lease creation and post-lock ownership checks use `clock_timestamp()`.

One row represents a logical job. Its scheduled occurrence timestamp stays unchanged across retries and changes only when successful recurrence advances. A failed or abandoned claim consumes an attempt; exhausted recurring jobs stop. Recurrence skips missed timestamps after completion.

## Why it is fragile

Lease recovery can run an occurrence more than once. Fencing protects queue state but cannot reverse effects performed by an old handler. Cancellation is cooperative and a running handler learns of it through its heartbeat. Database or shutdown failures must leave interrupted claims recoverable without refunding attempts.

## Safe-change guidance

- Use `(jobId, scheduledAt)` to deduplicate external effects; keep credentials outside payloads.
- Keep handler and heartbeat in one coroutine scope. Join the heartbeat before writing the outcome. Record handler cancellation as a failed attempt when the worker context remains active; propagate application cancellation.
- Reuse one service instance and one worker per host scope. Start workers explicitly with a supplied handler; stop application work before closing the datasource.
- Keep service migrations in their own Flyway location and history table so host and service versions cannot collide.

## Verification

Run `./gradlew :job-impl:test` with Docker available. Cover competing claims, expired final attempts, stale renewals and outcomes, cancellation races, retry identity, recurrence, and interrupted shutdown.

Database tests share the `jobTest` fixture, which cancels and joins worker coroutines before closing the connection pool.
