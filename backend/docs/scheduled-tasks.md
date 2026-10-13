# Scheduled tasks

## Invariant

The `SCHEDULING` tools `scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` manage the caller's [`JobService`](../../job-impl/docs/job-ownership.md) jobs; the trusted user ID is the job owner. Create stores the task instruction and, when the conversation is one of the user's channels, its channel as the default reply destination. Delete is `cancelJob`: the record stays in history and an occurrence that has already started is not interrupted.

`BackendRuntime.startBackgroundServices` starts the job worker with `ScheduledTaskRunner`. Each occurrence gets a hidden chat with client type `scheduled_task`, excluded from chat listings and channels like hook chats. Chat and execution UUIDs derive from `(jobId, scheduledAt)` with separate namespaces; the attempt number is excluded. PostgreSQL inserts the execution with `ON CONFLICT (id) DO NOTHING`; only the handler that inserts it may launch. Retries and competing workers reuse the records without modifying or relaunching them, including completed executions.

The handler returns after launch so the sequential worker keeps claiming due jobs. Up to `concurrentRuns` executions run per process; when every slot is taken, the handler waits and later jobs stay queued in PostgreSQL. A run that exceeds `runTimeoutMillis` is cancelled and joined before its slot is released.

Dispatch reserves one of `runsPerUserPerDay` launch slots in `scheduled_task_daily_launches`, keyed by user and the database's current UTC date. The conditional counter update and execution insertion share one transaction. Concurrent replicas cannot exceed the quota; duplicate occurrences reserve nothing, including after a day boundary. Completed, failed, cancelled or deleted executions do not refund usage. Cron bursts and overdue occurrences count on their actual dispatch day. A rejected reservation rolls back execution insertion and enters normal start retries; exhausting them fails that occurrence.

Create also checks planned runs as early feedback: a one-off counts once and a cron counts its next seven days divided by seven, rounded up, at least once. This planning estimate is soft and does not reserve launches. Cancelling a task removes its planned usage, but does not refund launches already reserved.

`requiresMessage` defaults to true for reminders and reports; set it false only for tasks that request no message. Creation requires `SendMessageToChannel` in the caller execution's enabled-tool snapshot. Dispatch checks the current enabled-tool snapshot again before persistence and launch, so permission changes cause explicit start failures. Action-only tasks remain available without delivery permission. Neither path adds a disabled tool to the execution catalog.

## Why it is fragile

A job's status reflects dispatch, not the agent's result or delivery. The result lives in the hidden chat, and messages reach the user only through `SendMessageToChannel`; its result must confirm delivery. A hidden final answer does not deliver a reminder. Tool availability is checked at dispatch; the normal immutable execution snapshot applies during an already launched run.

Launch is at most once while the occurrence records are retained. A crash after committing the execution but before launching the agent can leave the occurrence unexecuted; retries deliberately do not resume that execution. This does not guarantee exactly-once external effects or provide general execution recovery.

Failures before execution insertion use the job service's three attempts for the same occurrence. Exhausted one-offs become failed. Exhausted cron occurrences advance to the next future timestamp with a fresh attempt budget, including after final-attempt lease expiry. `lastFailure` explicitly retains the most recent exhausted occurrence's timestamp, finish time, attempts and error even after later success; it is not a full occurrence history. A persisted failed execution remains a failure on recovery and is never relaunched. Once an execution is persisted, its ID prevents another launch regardless of status.

Quota reservation is conservative: a persistence-before-launch crash or failed startup can consume allowance without an agent run. Daily counters have no automatic retention cleanup. Occurrence records must remain retained for deduplication. Existing users' saved default tool selections are not migrated; they must explicitly enable scheduling where absent.

Occurrence executions are process-local like ordinary chat executions; see the [distributed backend boundary](distributed-backend-boundary.md).

## Safe-change guidance

- Keep credentials and request-scoped data out of job payloads.
- Do not await agent completion inside the job handler; it serializes every user's tasks behind one worker.
- Keep hidden chats out of chat lists and channel providers whenever a client type is added.
- Change limits through `ScheduledTaskConfig` (`SOUZ_SCHEDULED_TASK_*`); raise them locally for development.

## Verification

Run `./gradlew :job-api:test :job-impl:test` and `./gradlew :backend:test` with Docker. Scheduling regressions cover delivery permissions, owner isolation, cancellation, transient/exhausted starts, lease recovery, completed one-offs, replica quota races, cron bursts beyond the planning week, UTC rollover, timeout cancellation, competing handlers, completed retries, distinct occurrences and a crash between persistence and launch.
