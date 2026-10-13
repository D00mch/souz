# Scheduled tasks

## Invariant

The `SCHEDULING` tools `scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` manage the caller's [`JobService`](../../job-impl/docs/job-ownership.md) jobs; the trusted user ID is the job owner. Create stores the task instruction and, when the conversation is one of the user's channels, its channel as the default reply destination. Delete is `cancelJob`: the record stays in history and an occurrence that has already started is not interrupted.

`BackendRuntime.startBackgroundServices` starts the job worker with `ScheduledTaskRunner`. Each occurrence gets a hidden chat with client type `scheduled_task`, excluded from chat listings and channels like hook chats. Chat and execution UUIDs derive from `(jobId, scheduledAt)` with separate namespaces; the attempt number is excluded. PostgreSQL inserts the execution with `ON CONFLICT (id) DO NOTHING`; only the handler that inserts it may launch. Retries and competing workers reuse the records without modifying or relaunching them, including completed executions.

The handler returns after launch so the sequential worker keeps claiming due jobs. Up to `concurrentRuns` executions run per process; when every slot is taken, the handler waits and later jobs stay queued in PostgreSQL. A run that exceeds `runTimeoutMillis` is cancelled and joined before releasing its slot.

Create rejects a task when the user's planned runs per day would exceed `runsPerUserPerDay`. A one-off task counts once and a cron task counts its occurrences over the next seven days divided by seven, rounded up, at least once. This is a soft admission limit: simultaneous creates can exceed it. Cancelling a task returns its planned runs to this estimate; it never refunds launches.

Dispatch enforces a separate durable limit of `runsPerUserPerDay` reservations per user per UTC calendar day, using PostgreSQL time. The execution insert and conditional increment of `scheduled_task_daily_launches` commit together. Concurrent replicas contend on the same `(user_id, launch_day)` counter; a duplicate execution insert does not charge it. Completed/failed one-offs, cron bursts outside the planning window, and deleted execution history cannot refund the counter. Quota rejection rolls back the execution insert and follows the normal occurrence failure/retry path. Reservations are charged before launch and are not refunded after a crash or failure.

Create defaults `requiresDelivery` to true for reminders and tasks that send messages; callers set false only for action-only work. Reminder creation requires `SendMessageToChannel` in the creating execution's effective enabled-tool snapshot. Dispatch checks the prepared execution's enabled-tool snapshot again before insertion, so permission revocation produces an explicit retryable dispatch error, without granting or injecting a disabled tool. Legacy payloads without the flag also require delivery. A recovered persisted occurrence is deduplicated before resolving changed settings.

## Why it is fragile

A job's status reflects dispatch, not the agent's result or a delivery receipt. The result lives in the hidden chat, and messages reach the user only through `SendMessageToChannel`. Its result reports delivery success/failure; neither successful creation nor dispatch proves that the model invoked it or that a provider delivered a message. The flag declares intent; the host does not infer reminder intent from arbitrary prompt text.

Launch is at most once while the occurrence records are retained. A crash after committing the execution but before launching the agent can leave the occurrence unexecuted; retries deliberately do not resume that execution. This does not guarantee exactly-once external effects or provide general execution recovery.

Failures before execution insertion use the job service's three attempts, retaining the same occurrence ID. Scheduled jobs opt into `continueAfterFailure`: exhaustion records a `job_occurrence_failures` row and advances cron to its next future occurrence, while a one-off becomes `FAILED`. Expired final leases take the same path. `scheduled-task.list` exposes owner-scoped `failedOccurrences`, retained even after later success. Generic JobService callers keep the default stop-on-exhaustion policy.

Once an execution is persisted, its ID prevents another launch regardless of its status. A retry that finds a failed execution propagates the failure rather than declaring dispatch success. A queued/running record left by an interrupted launch remains deduplicated and may remain stranded; failure history cannot resolve that ambiguous crash window. Occurrence records must be retained for deduplication, and daily counters/failure history currently have no automatic retention cleanup.

Occurrence executions are process-local like ordinary chat executions; see the [distributed backend boundary](distributed-backend-boundary.md).

## Safe-change guidance

- Keep credentials and request-scoped data out of job payloads.
- Do not await agent completion inside the job handler; it serializes every user's tasks behind one worker.
- Keep hidden chats out of chat lists and channel providers whenever a client type is added.
- Change limits through `ScheduledTaskConfig` (`SOUZ_SCHEDULED_TASK_*`); raise them locally for development.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduled*' --tests 'ru.souz.backend.scheduler.*'` with Docker. Cover delivery permissions, owner isolation, cancellation, validation, quota contention/rollover, completed one-offs, sparse cron bursts, concurrency and timeout, competing handlers, completed retries, distinct occurrences and a crash between persistence and launch. Run `./gradlew :job-api:test :job-impl:test` for transient retry, exhausted occurrence history, final-lease recovery, fencing, and future recurrence.
