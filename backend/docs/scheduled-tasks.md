# Scheduled tasks

## Invariant

The `SCHEDULING` tools `scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` manage the caller's [`JobService`](../../job-impl/docs/job-ownership.md) jobs; the trusted user ID is the job owner. Create stores the task instruction, its delivery semantics and, when the conversation is one of the user's channels, its channel as the default reply destination. Delete is `cancelJob`: the record stays in history and an occurrence that has already started is not interrupted.

`BackendRuntime.startBackgroundServices` starts the job worker with `ScheduledTaskRunner`. Each occurrence gets a hidden chat with client type `scheduled_task`, excluded from chat listings and channels like hook chats. Chat and execution UUIDs derive from `(jobId, scheduledAt)` with separate namespaces. PostgreSQL inserts the execution with `ON CONFLICT (id) DO NOTHING`; only the handler that inserts it may launch.

The agent chooses the delivery semantics at creation with `atMostOnce`:

- At least once (default): the IDs also include the attempt number. Competing workers on one attempt launch once, but a retry after an uncertain outcome, such as a lost lease or an unpersisted finish, launches a new execution and can repeat its effects.
- At most once: the IDs exclude the attempt number. Retries and competing workers reuse the records without modifying or relaunching them, including completed executions.

The handler returns after launch so the sequential worker keeps claiming due jobs. Up to `concurrentRuns` executions run per process; when every slot is taken, the handler waits and later jobs stay queued in PostgreSQL. A run that exceeds `runTimeoutMillis` is cancelled and joined before releasing its slot.

Create rejects a task when the user's planned runs per day would exceed `runsPerUserPerDay`. A one-off task counts once and a cron task counts its occurrences over the next seven days divided by seven, rounded up, at least once. This is a soft admission limit: simultaneous creates can exceed it. Cancelling a task returns its planned runs to this estimate; it never refunds launches.

Dispatch enforces a separate durable limit of `runsPerUserPerDay` reservations per user per UTC calendar day, using PostgreSQL time. The execution insert and conditional increment of `scheduled_task_daily_launches` commit together. Concurrent replicas contend on the same `(user_id, launch_day)` counter; a duplicate execution insert does not charge it, while an at-least-once relaunch does. Quota rejection rolls back the execution insert and follows the normal occurrence failure and retry path. Reservations are not refunded after a crash or failure.

Create defaults `requiresDelivery` to true for reminders and tasks that send messages; it is false only for action-only work. Such a task requires `SendMessageToChannel` in the creating execution's enabled-tool snapshot, and dispatch checks the owner's current snapshot again before insertion, so a revoked permission produces an explicit retryable dispatch error without enabling the tool. Payloads without the flag also require delivery.

## Why it is fragile

A job's status reflects dispatch, not the agent's result or a delivery receipt. The result lives in the hidden chat, and messages reach the user only through `SendMessageToChannel`. Neither creation nor dispatch proves that the model invoked it or that a provider delivered the message.

Neither semantics recovers an execution. The job records dispatch success once the agent has launched, so a crash while the agent runs loses that occurrence under both semantics. An at-most-once crash after committing the execution but before launching it also leaves the occurrence unexecuted, and its queued record may remain stranded.

Failures before execution insertion use the job service's three attempts. Scheduled jobs opt into `continueAfterFailure`: exhaustion records a `job_occurrence_failures` row and advances cron to its next future occurrence, while a one-off becomes `FAILED`. Expired final leases take the same path. `scheduled-task.list` reads the owner's failures in one query and shows each task's latest three. A retry that finds a failed at-most-once execution propagates the failure rather than declaring dispatch success. Occurrence records, daily counters and failure history have no automatic retention cleanup.

Occurrence executions are process-local like ordinary chat executions; see the [distributed backend boundary](distributed-backend-boundary.md).

## Safe-change guidance

- Keep credentials and request-scoped data out of job payloads.
- Do not await agent completion inside the job handler; it serializes every user's tasks behind one worker.
- Keep hidden chats out of chat lists and channel providers whenever a client type is added.
- Change limits through `ScheduledTaskConfig` (`SOUZ_SCHEDULED_TASK_*`); raise them locally for development.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduled*' --tests 'ru.souz.backend.scheduler.*'` with Docker. Cover both delivery semantics, delivery permissions, owner isolation, cancellation, validation, quota contention and rollover, concurrency and timeout, and a crash between persistence and launch. Run `./gradlew :job-impl:test` for transient retry, exhausted occurrence history, final-lease recovery and future recurrence.
