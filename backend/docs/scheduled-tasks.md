# Scheduled tasks

## Invariant

The `SCHEDULING` tools `scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` manage the caller's [`JobService`](../../job-impl/docs/job-ownership.md) jobs; the trusted user ID is the job owner. Create stores the task instruction, a `recurring` flag and, when the conversation is one of the user's channels, its channel as the default reply destination. Delete is `cancelJob`: the record stays in history and an occurrence that has already started is not interrupted.

`BackendRuntime.startBackgroundServices` starts the job worker with `ScheduledTaskRunner`. Each occurrence gets a new hidden chat with client type `scheduled_task`, excluded from chat listings and channels like hook chats. The handler starts the agent execution and returns, so the sequential worker keeps claiming due jobs. Up to `concurrentRuns` executions run per process; when every slot is taken, the handler waits and later jobs stay queued in PostgreSQL. A run that exceeds `runTimeoutMillis` is cancelled.

Create rejects a task when the user's planned runs per day would exceed `runsPerUserPerDay`. A one-off task counts once and a cron task counts its occurrences over the next seven days divided by seven, rounded up, at least once. The budget limits planned work, not executions already completed.

## Why it is fragile

A job's status reflects whether its occurrence started, not the agent's result. The result lives in the hidden chat, and messages reach the user only through `SendMessageToChannel`.

The job service stops a recurring job after three failed attempts in a row, about a minute apart. The handler therefore throws only for one-off tasks; a recurring occurrence that cannot start is logged and the schedule continues. Lease recovery can start an occurrence twice, and duplicate runs are accepted.

Occurrence executions are process-local like ordinary chat executions; see the [distributed backend boundary](distributed-backend-boundary.md).

## Safe-change guidance

- Keep credentials and request-scoped data out of job payloads.
- Do not await agent completion inside the job handler; it serializes every user's tasks behind one worker.
- Keep hidden chats out of chat lists and channel providers whenever a client type is added.
- Change limits through `ScheduledTaskConfig` (`SOUZ_SCHEDULED_TASK_*`); raise them locally for development.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduledTasksE2eTest' --tests 'ru.souz.backend.scheduler.*'` with Docker. Cover delivery to the creating conversation, owner isolation, cancellation, validation and budget, concurrency and timeout, and start failures of recurring and one-off tasks.
