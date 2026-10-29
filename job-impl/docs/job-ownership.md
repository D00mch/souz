# Job ownership and recovery

## Invariant

One worker calls the host's `awaitCapacity`, claims the earliest eligible job atomically with `FOR UPDATE SKIP LOCKED`, and commits before running its handler. The callback may wait for capacity and returns owners to exclude; their jobs remain pending without spending attempts. Unsuccessful claims use the default five-second polling delay. PostgreSQL time governs eligibility and lease validity. Renewals and outcome writes lock the row before validating the matching token, running status, and lease against current database time in the same transaction.

Recover expired running leases before claiming: requeue attempts below the limit with their existing availability time, and fail exhausted attempts. Both eligibility scans use `statement_timestamp()` as an index range boundary; the ordered claim reads only pending rows. Recovery skips locked rows. Lease creation and post-lock ownership checks use `clock_timestamp()`.

One row represents a logical job. Its scheduled occurrence timestamp stays unchanged across retries and changes only when recurrence advances. A failed or abandoned claim consumes an attempt. Exhaustion records an owner-scoped `job_occurrence_failures` entry, including final expired leases. Recurring jobs stop by default; hosts can set `continueAfterFailure` at creation to advance to the next future cron occurrence after recording the failure. One-offs still fail. Occurrence failures survive later successful runs and are available through `listFailedOccurrences`, which reads the latest three per requested job. `listJobs` filters by status and limits in SQL, ordered by `(created_at, id)` descending; its cursor is the last job ID of the previous page. Recurrence skips missed timestamps after completion.

`bindMetrics` registers `souz.jobs.oldest.due` (Prometheus: `souz_jobs_oldest_due_seconds`) without IO. Each gauge read queries the oldest due pending job in PostgreSQL time, including excluded owners; future jobs do not contribute, and an empty due queue reports zero. The host scrapes on an IO dispatcher. This label-free gauge describes shared database state; aggregate replicas with `max`. Execution metrics belong to the host.

## Why it is fragile

Lease recovery can run an occurrence more than once. Fencing protects queue state but cannot reverse effects performed by an old handler. Cancellation is cooperative and a running handler learns of it through its heartbeat. Database or shutdown failures must leave interrupted claims recoverable without refunding attempts.

## Safe-change guidance

- Use `(jobId, scheduledAt)` to deduplicate external effects; keep credentials outside payloads.
- Keep handler and heartbeat in one coroutine scope. Join the heartbeat before writing the outcome. Record handler cancellation as a failed attempt when the worker context remains active; propagate application cancellation.
- Reuse one service instance and one worker per host scope. Start workers explicitly with a supplied handler; stop application work before closing the datasource.
- Keep service migrations in their own Flyway location and history table so host and service versions cannot collide.

## Verification

Run `./gradlew :job-impl:test` with Docker available. Cover excluded owners, queue age, competing claims, expired final attempts, stale renewals and outcomes, cancellation races, retry identity, opt-in recurrence after exhaustion, durable occurrence failures, bounded listing pages, and interrupted shutdown.

Database tests share the `jobTest` fixture, which cancels and joins worker coroutines before closing the connection pool.
