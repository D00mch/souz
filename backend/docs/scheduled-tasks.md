# Scheduled agent tasks

`scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` are backend `SCHEDULING` tools discovered through the existing Skill catalog. `JobService` owns schedules, persistence, leases, retries and owner-scoped cancellation.

Create accepts a self-contained prompt, an optional title, and exactly one of future RFC 3339 `runAt`, positive `afterSeconds`, or five-field Unix `cron`. Cron uses an explicit IANA `timeZone` or the user's zone. Delete cancels future dispatches and keeps history; an execution already admitted is not interrupted. List defaults to active jobs; `includeInactive` includes completed, failed and cancelled jobs, with the latest three exhausted dispatch failures for each job.

`BackendRuntime` starts the job worker with `ScheduledTaskRunner`. Each occurrence uses an idempotently created hidden `scheduled_task` chat. `AgentExecutionService` reserves its execution atomically before starting it and owns its registered coroutine, concurrency and timeout. Capacity is acquired inside that coroutine; waiting executions remain registered for shutdown. Hidden chats do not appear in chat/channel lists and cannot wait for interactive options.

The final answer stays in the hidden chat. The instruction tells the agent to use `SendMessageToChannel` only when the task calls for a message, defaulting to the originating conversation. `requiresDelivery` defaults to true and checks messaging permission at creation and dispatch. Action-only tasks set it to false.

## Delivery and limits

The agent chooses `atMostOnce` for each task. False (default) includes the job attempt in the execution identity: retrying an uncertain dispatch may duplicate effects. True identifies only the job and scheduled occurrence: an admitted occurrence never relaunches, even if a crash happened before launch. Both modes deduplicate competing handlers for the same identity. Neither recovers an agent execution interrupted by a process crash; see [the distributed boundary](distributed-backend-boundary.md).

The job handler returns after launch. Job status describes dispatch, not agent completion or message delivery. Startup errors use JobService's three attempts. Scheduled cron jobs opt into `continueAfterFailure`, retaining exhausted occurrences while advancing to the next future run; one-offs fail after exhaustion.

- `SOUZ_SCHEDULED_TASK_RUNS_PER_USER_PER_DAY` (100) limits execution admissions per owner per UTC day. Existing execution rows are the durable ledger; an owner-row lock serializes the count and insert across replicas. Duplicates consume nothing; failed starts remain charged. Keep scheduled `started_at` stable after admission and retain execution history for quota accounting.
- `SOUZ_SCHEDULED_TASK_CONCURRENT_RUNS` (16) limits running scheduled agents per process.
- `SOUZ_SCHEDULED_TASK_RUN_TIMEOUT_MILLIS` (300000) bounds a run after it acquires capacity. Coroutine cancellation unwinds the agent before releasing capacity; the existing launcher persists cancellation and joins work at shutdown.

The daily quota is enforced at dispatch, without a separate cron frequency estimate. A rejected occurrence follows normal startup retries. Recurring tasks continue on later occurrences.

Saved explicit `enabledTools` selections continue to apply. Existing default snapshots do not automatically gain newly advertised tools; this is the settings migration tracked separately from scheduling.

## Verification

Run `./gradlew :backend:test :job-impl:test :agent:test` with Docker available. Scheduling E2E tests use the production Skill catalog, agent kernel and PostgreSQL; external model responses are scripted.
