# Scheduled agent tasks

`scheduled-task.create`, `scheduled-task.list` and `scheduled-task.delete` are backend `SCHEDULING` tools discovered through the existing Skill catalog. `JobService` owns schedules, persistence, leases, retries and owner-scoped cancellation.

Create accepts a self-contained prompt, an optional title, and exactly one of future RFC 3339 `runAt`, positive `afterSeconds`, or five-field Unix `cron`. Cron uses an explicit IANA `timeZone` or the user's zone. Delete cancels future dispatches and keeps history; an execution already admitted is not interrupted. List defaults to active jobs; `includeInactive` includes completed, failed and cancelled jobs. Pages contain 20 jobs by default (`limit`: 1–100), newest first. Pass `nextCursor` as `cursor` to continue until it is null. Database queries filter and limit jobs before fetching the latest three exhausted dispatch failures for each returned job.

`BackendRuntime` starts a bounded job-worker group with `ScheduledTaskRunner`. Each lane claims one occurrence, creates its idempotent hidden `scheduled_task` chat, and waits for the registered execution lifecycle to finish before claiming again. Excess jobs remain unclaimed in PostgreSQL, with no execution admission or retry consumption while waiting for capacity. `AgentExecutionService` reserves executions atomically and owns their registered coroutines and timeouts. Hidden chats do not appear in chat/channel lists and cannot wait for interactive options.

The final answer stays in the hidden chat. The instruction tells the agent to use `SendMessageToChannel` only when the task calls for a message, defaulting to the originating conversation. `requiresDelivery` defaults to true and checks messaging permission at creation and dispatch. Action-only tasks set it to false, removing `SendMessageToChannel` from both the persisted tool snapshot and runtime request.

Rejected dispatches remove their empty hidden chat under the same chat-row lock used by execution admission. Cleanup preserves any chat with an execution or messages, including a competing handler's admission, and finishes before releasing the worker lane. A process crash between chat creation and admission can still leave an empty chat; this cleanup does not provide crash recovery.

## Delivery and limits

The agent chooses `atMostOnce` for each task. False (default) includes the job attempt in the execution identity: retrying an uncertain dispatch may duplicate effects. True identifies only the job and scheduled occurrence: an admitted occurrence never relaunches, even if a crash happened before launch. Both modes deduplicate competing handlers for the same identity. A duplicate of a failed execution fails with its stored error, including a competing insertion; it cannot turn a failed dispatch into success. Neither mode recovers an agent execution interrupted by a process crash; see [the distributed boundary](distributed-backend-boundary.md).

The job handler waits for execution and cancellation cleanup, including cancellation persistence, before returning or propagating worker cancellation. The lease remains active during this wait. Its outcome describes dispatch acceptance; agent errors after startup remain on the execution and do not trigger dispatch retries, and job success is not a delivery receipt. Startup errors use JobService's three attempts. Scheduled cron jobs opt into `continueAfterFailure`, retaining exhausted occurrences while advancing to the next future run; one-offs fail after exhaustion.

- `SOUZ_SCHEDULED_TASK_RUNS_PER_USER_PER_DAY` (100) limits execution admissions per owner per UTC day. Existing execution rows are the durable ledger; an owner-row lock serializes the count and insert across replicas. Duplicates consume nothing; failed starts remain charged. Keep scheduled `started_at` stable after admission and retain execution history for quota accounting.
- `SOUZ_SCHEDULED_TASK_CONCURRENT_RUNS` (16) sets the number of worker lanes per process, bounding admitted scheduled executions through cleanup.
- `SOUZ_SCHEDULED_TASK_RUN_TIMEOUT_MILLIS` (300000) bounds agent runtime after launch. A lane remains occupied until the existing launcher finishes execution and cancellation cleanup; shutdown joins that work before closing resources.

Creation-time budget checks are intentionally omitted as a product tradeoff: schedule acceptance does not reserve daily capacity or guarantee that its runs will be admitted. An accepted schedule can later have runs rejected by the daily quota. Dispatch uses actual admissions instead of estimating cron frequency; a rejected occurrence follows normal startup retries, and recurring tasks continue on later occurrences.

Saved explicit `enabledTools` selections continue to apply. Existing default snapshots do not automatically gain newly advertised tools; this is the settings migration tracked separately from scheduling.

## Verification

Run `./gradlew :backend:test :job-impl:test :agent:test` with Docker available. Scheduling E2E tests use the production Skill catalog, agent kernel and PostgreSQL; external model responses are scripted.
