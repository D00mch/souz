# Scheduled tasks

## Ownership and dispatch

The `SCHEDULING` tools manage jobs for the trusted caller only. Creation defaults replies to the originating channel when it belongs to the owner. Cancellation retains history and leaves an already launched execution running. Keep credentials and request-scoped data out of job payloads. [Tool inputs and paging](../src/main/kotlin/ru/souz/backend/scheduler/ScheduledTaskTools.kt)

Each occurrence uses a hidden `scheduled_task` chat, excluded from chat lists and channel discovery. Scheduled executions skip interactive options. Job success records dispatch. The final answer stays in the hidden chat, and delivery depends on `SendMessageToChannel`. Tasks that require delivery, including legacy payloads without the flag, require the tool in both the creating execution's snapshot and the owner's settings at dispatch. Revocation fails dispatch before admission.

- **At least once (default).** Chat and execution IDs include the job, scheduled time, and attempt. A retry can launch a new execution and repeat effects.
- **At most once.** IDs omit the attempt. Retries reuse the existing records without relaunching. An existing failed execution still fails dispatch.

Only the caller that inserts an execution may launch it. Empty-chat cleanup must preserve concurrent admissions. A crash after insertion but before launch can strand an at-most-once occurrence. A crash after dispatch can lose a running occurrence under either mode. [Distributed backend boundary](distributed-backend-boundary.md) · [Execution admission](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt)

## Capacity and quotas

Route job kinds through the worker's single sequential handler. Await capacity before claiming so busy owners' jobs stay pending without spending attempts. The handler returns after launch. Each process allows one active execution per owner and a configurable total. Release slots only after execution cleanup, including timeout and shutdown. Overload can delay jobs indefinitely. [Scheduler and configuration](../src/main/kotlin/ru/souz/backend/scheduler/ScheduledTaskRunner.kt)

The creation budget estimates planned runs from active tasks and can be exceeded by concurrent creates. Dispatch enforces a separate durable per-owner quota per UTC day using PostgreSQL time. Charge each new execution in its insert transaction. Duplicates spend nothing. Crashes and failures leave reservations spent. Quota rejection rolls back admission and follows normal retries. [Quota transaction](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresAgentExecutionRepository.kt)

Scheduled jobs opt into advancing cron after exhaustion and retain occurrence failures through the [job service](../../job-impl/docs/job-ownership.md). Occurrence records, launch counters, and failure history have no automatic retention cleanup.

## Verification

For runtime changes, run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduled*' --tests 'ru.souz.backend.scheduler.*'` with Docker. Run `./gradlew :job-impl:test` for retry and lease changes.
