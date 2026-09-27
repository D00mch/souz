# Scheduled tasks on Souz

Souz provides five compiled Kotlin tools in `SCHEDULING`: `scheduled-task.create`, `.get`, `.list`, `.update`, and `.delete`. Skill discovery and `RunSkillCommand` expose them subject to the execution's `enabledTools`; there are no scheduler SKILL.md files. Orion's scheduler service is a separate implementation dependency. Its wire contract is defined in the [Orion protocol](orion-scheduler-contract.md).

## Prerequisites

- The owner is enabled in `SOUZ_HOOK_OWNERS` and has a writable LOCAL or DOCKER workspace under the existing [hook policy](hooks.md).
- An owned, unarchived `backend` chat has a live WebSocket subscription on this Souz process, connected to an Orion implementation of `scheduler.*`.
- All Orion channels for the owner address the same scheduler. Capability discovery, multiple Souz instances and failover are outside this version.

`channelId` is optional on every tool. It selects an owned live channel explicitly; otherwise Souz prefers the originating channel and then any eligible live channel. Hook-origin executions use the same selection without needing a public execution thread. An unavailable channel fails before file mutation.

## Tool arguments

| Tool | Arguments besides optional channelId |
|---|---|
| create | Required title, prompt, schedule |
| get | Required taskId |
| list | includeInactive, default false |
| update | Required taskId and at least one of title, prompt, schedule |
| delete | Required taskId |

Title is nonblank and at most 200 characters. Prompt is nonblank and at most 16384 characters including the generated delivery instruction, subject to the hook file's existing byte limit. Unknown fields and explicit nulls are rejected. The prompt runs in a fresh hidden technical chat and must include the required context.

Souz appends a delivery instruction to the prompt, using the originating conversation's owned channel descriptor as the default reply destination. The channel selected for scheduler RPC does not determine where the answer goes. When the task requires a user message, the agent must use `SendMessageToChannel`; the technical chat's final answer is not automatically delivered. An explicit destination or silent-execution instruction in the task takes precedence. Prompt updates preserve the original default even when issued from another chat; explicit destination changes belong in the task instruction. Hooks created without a user-facing source channel have no automatic destination. Agents creating child tasks must carry the required destination into their prompts. Existing hooks are not retroactively rewritten.

Schedules:

```json
{"kind":"after","seconds":600}
{"kind":"once","runAt":"2030-09-25T12:10:00Z"}
{"kind":"cron","expression":"0 8 * * 1-5","timeZone":"Europe/Moscow"}
```

`after` is create-only and becomes one absolute `once` using the backend clock. `once` requires a future RFC3339 instant with an offset. Cron has five numeric Unix fields, an explicit IANA zone, and `*` in either day-of-month or day-of-week. Souz checks the structural contract; Orion validates the cron ranges and computes future occurrences, including DST. No cron runtime or execution loop runs on Souz.

## Ownership and storage

Every created task receives one ordinary version-1 Bearer hook with `taskId = hookId = scheduleId = task-<UUID>`. Orion owns title, schedule and delivery state. Souz owns the prompt, hook definition and execution receipts; no schedule copy or association table is stored in its database. Hook YAML has no task marker or purpose field.

The generated token has 32 random bytes. Only its SHA-256 is saved in hook YAML; the raw token is sent in `scheduler.create.arguments.target.bearerToken`. Every internal RPC uses transient `tool.call.started` with `seq:null`, an independent correlation `threadId` and a 30-second deadline. These internal arguments/results bypass durable events and tool-call storage. Tool results and remote errors are decoded into bounded contract fields; raw parser or remote error messages are not returned. Unrecognized delivery error codes become `delivery_error`.

## Operation results and partial failures

Mutations return `status` (`succeeded`, `partial`, `failed`), `taskId`, `hookStatus`, and `scheduleStatus`, plus known metadata and a safe error code. Only `succeeded` confirms completion. `missing` means confirmed absence, `unavailable` means a read could not complete, and `unknown` means a sent mutation has an uncertain outcome. Unknown schedule results omit schedule and nextRunAt.

- Create writes and reloads an enabled hook before registering the schedule. Explicit rejection known to leave no schedule allows cleanup of this invocation's unchanged new hook. Timeout, cancellation or an invalid success response leaves it for inspection.
- Get/list query Orion first. Get adds the prompt and latest execution result. List consumes up to 100 pages of 200 records, deduplicates IDs, rejects cursor loops and loads latest owner-scoped receipts in one SQL query. Its `lastExecution.receiptStatus` is the receipt lifecycle, not delivery success or the agent's final status; get provides detailed execution state. The default list keeps active/dispatching schedules with enabled hooks; includeInactive also includes missing/disabled hooks and terminal schedules.
- Update first confirms the schedule and hook ownership. Prompt changes write/reload the hook; title and schedule changes go only to Orion. Mixed changes do not roll back a committed prompt when the remote update fails. Disabled hooks cannot be resumed through update.
- Delete first confirms the schedule, disables/reloads the hook, then removes the schedule and finally the unchanged hook definition. Uncertain cancellation leaves the hook disabled. Receipts and execution history remain. A missing hook does not prevent deleting its schedule.

Existing hooks are never changed or removed when the initial schedule lookup is missing or unavailable. A leftover hook without a schedule needs explicit operator cleanup: inspect the exact ID and ownership, remove the appropriate definition, then use the existing hook reload endpoint. Do not retry create blindly or infer task ownership from the `task-` prefix. Deleting a recurring task preserves independently created one-off tasks and already running agents.

Mutation locks cover the full operation, including RPC, per workspace: one shared LOCAL workspace lock or one DOCKER owner workspace lock. These are process-local locks; they do not serialize manual filesystem edits. Before a later write/removal, the tool checks the expected definition and revision again. It deletes hook.yaml and an empty containing directory only, leaving unrelated files intact. Creation respects the loader's 100-directory workspace limit.

Task purpose classification, retry delivery, distributed coordination, orphan reconciliation and automatic cleanup of completed tasks are deferred; see [pain points](../backend/docs/pain-points/scheduled-tasks.md).

## Implementation and verification

- [Tools and schemas](../backend/src/main/kotlin/ru/souz/backend/scheduler/ScheduledTaskTools.kt)
- [Operation coordination](../backend/src/main/kotlin/ru/souz/backend/scheduler/ScheduledTaskService.kt)
- [Workspace mutations](../backend/src/main/kotlin/ru/souz/backend/scheduler/ScheduledHookFiles.kt)
- [Orion client and response validation](../backend/src/main/kotlin/ru/souz/backend/scheduler/OrionSchedulerClient.kt)
- [Shared live transport](../backend/src/main/kotlin/ru/souz/backend/client/LiveClientToolDispatcher.kt)
- [Production-wired E2E tests](../backend/src/test/kotlin/ru/souz/backend/e2e/BackendScheduledTasksE2eTest.kt)

Run `./gradlew :backend:test :agent:test souzGateFast` with Docker available. E2E uses PostgreSQL, real Ktor routes and the agent runtime; only the Orion WebSocket peer and LLM are simulated. End-to-end scheduling at wall-clock time additionally requires the Orion service.
