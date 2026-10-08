# Job ownership and recovery

## Invariant

Claim the earliest eligible job atomically with `FOR UPDATE SKIP LOCKED` and commit before running its handler. PostgreSQL time governs eligibility and lease validity. Renewals and outcome writes require the matching token, running status, and an unexpired lease.

One row represents a logical job. Its scheduled occurrence timestamp stays unchanged across retries and changes only when successful recurrence advances. A failed or abandoned claim consumes an attempt; exhausted recurring jobs stop. Recurrence skips missed timestamps after completion.

## Why it is fragile

Lease recovery can run an occurrence more than once. Fencing protects queue state but cannot reverse effects performed by an old handler. Cancellation is cooperative and a running handler learns of it through its heartbeat. Database or shutdown failures must leave interrupted claims recoverable without refunding attempts.

## Safe-change guidance

- Use `(jobId, scheduledAt)` to deduplicate external effects; keep credentials outside payloads.
- Keep handler and heartbeat in one coroutine scope. Join the heartbeat before writing the outcome, and propagate application cancellation.
- Reuse one service instance and one worker per host scope. Start workers explicitly with a supplied handler; stop application work before closing the datasource.
- Keep service migrations in their own Flyway location and history table so host and service versions cannot collide.

## Verification

Run `./gradlew :job-impl:test` with Docker available. Cover competing claims, expired final attempts, stale renewals and outcomes, cancellation races, retry identity, recurrence, and interrupted shutdown.
