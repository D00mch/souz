# Scheduled tasks

## Invariant

The `SCHEDULING` tools `scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` manage the caller's [`JobService`](../../job-impl/docs/job-ownership.md) jobs; the trusted user ID is the job owner. Create stores the task instruction and, when the conversation is one of the user's channels, its channel as the default reply destination. Delete is `cancelJob`: the record stays in history and an occurrence that has already started is not interrupted.

`BackendRuntime.startBackgroundServices` starts the job worker with `ScheduledTaskRunner`. Each occurrence gets a hidden chat with client type `scheduled_task`, excluded from chat listings and channels like hook chats. Chat and execution UUIDs derive from `(jobId, scheduledAt)` with separate namespaces; the attempt number is excluded. PostgreSQL inserts the execution with `ON CONFLICT (id) DO NOTHING`; only the handler that inserts it may launch. Retries and competing workers reuse the records without modifying or relaunching them, including completed executions.

The handler returns after launch so the sequential worker keeps claiming due jobs. Up to `concurrentRuns` executions run per process; when every slot is taken, the handler waits and later jobs stay queued in PostgreSQL. A run that exceeds `runTimeoutMillis` is cancelled.

Create rejects a task when the user's planned runs per day would exceed `runsPerUserPerDay`. A one-off task counts once and a cron task counts its occurrences over the next seven days divided by seven, rounded up, at least once. This is a soft admission limit: simultaneous creates can exceed it. Cancelling a task returns its planned runs to the budget.

## Why it is fragile

A job's status reflects dispatch, not the agent's result. The result lives in the hidden chat, and messages reach the user only through `SendMessageToChannel`.

Launch is at most once while the occurrence records are retained. A crash after committing the execution but before launching the agent can leave the occurrence unexecuted; retries deliberately do not resume that execution. This does not guarantee exactly-once external effects or provide general execution recovery.

Failures before execution insertion use the job service's normal retries; both one-off and recurring jobs stop after exhausting their attempts. Once an execution is persisted, its ID prevents another launch regardless of its status.

Occurrence executions are process-local like ordinary chat executions; see the [distributed backend boundary](distributed-backend-boundary.md).

## Safe-change guidance

- Keep credentials and request-scoped data out of job payloads.
- Do not await agent completion inside the job handler; it serializes every user's tasks behind one worker.
- Keep hidden chats out of chat lists and channel providers whenever a client type is added.
- Change limits through `ScheduledTaskConfig` (`SOUZ_SCHEDULED_TASK_*`); raise them locally for development.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduledTasksE2eTest' --tests 'ru.souz.backend.scheduler.*'` with Docker. Cover delivery, owner isolation, cancellation, validation and budget, concurrency and timeout, competing handlers, completed retries, distinct occurrences and a crash between persistence and launch.
