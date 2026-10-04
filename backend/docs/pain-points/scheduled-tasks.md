# Scheduled task scope

## Invariant

The [Souz scheduled-task tools](../../../docs/scheduled-tasks.md) leave the hook manifest unchanged; the Orion service follows the separate [wire contract](../../../docs/orion-scheduler-contract.md). Orion owns task titles and schedules; Souz owns the hook instruction and execution results. A scheduler record and matching ownership establish the association before an existing hook is changed or deleted.

Task purpose classification is outside this version's scope. There is no user/maintenance field, implicit classification, or purpose-based list filter. Integration upkeep, such as Gmail watch renewal, uses the same task operations and list as reminders.

Management needs an owned live Orion channel on the same process. All internal scheduler calls, including same-channel calls, use the transient dispatcher; a create call contains a raw Bearer credential and must never use durable same-thread client-tool storage. Mutation locks span file writes, reload and the remote call per workspace (shared LOCAL; owner-scoped DOCKER). They do not protect operator filesystem edits. Keep the hook loader owner-scoped: another owner's readable but invalid manifest must not disable these operations. An unrecognized manifest at a task's expected path is a configuration conflict, not a missing hook.

## Why it is fragile

A task title or prompt is not a reliable type marker. Treating every listed task as a reminder can make a request to delete reminders affect integration upkeep. The initial contract provides neither a purpose-based deletion guard nor a bulk-delete operation.

Without a local task marker, a hook left behind by uncertain schedule creation cannot be identified as a scheduled task from its manifest alone. A missing scheduler record and an unavailable scheduler are different states; neither permits a general task tool to delete an existing hook.

## Safe-change guidance

- Validate the scheduler record before existing-hook mutation; recheck the local definition before follow-up writes or cleanup. Missing records and read failures stop without touching the hook. Disabled hooks cannot be resumed by update.
- Keep cancellation propagating, never retry a sent mutation on another channel, and return unknown outcomes without a claimed schedule. Preserve independently created one-off tasks on parent deletion.
- Treat receipt lifecycle, HTTP delivery and agent completion separately. List batches latest receipts; get includes execution detail and the final result.
- Preserve the source-channel delivery block inside the ordinary hook prompt during updates. Scheduler RPC channels and hidden hook chats are not default reply destinations. Child tasks need the agent to carry over their destination; a final technical-chat answer alone never notifies the user.
- A default delivery destination does not require a notification. Creation instructions must not add success reports to action-only tasks unless the user requested them.
- Keep purpose fields and filters out of the initial implementation and do not infer them from names or prompts.
- Operate on the specific tasks selected by the user; do not translate a reminder-only request into deletion of every scheduler record.
- Before adding purpose-aware lists or deletion, define the classification and treatment of existing records explicitly. Keep task metadata with the scheduler rather than expanding the hook manifest.
- Preserve the distinction between failed, partially completed, and unknown operations. Cleanup within a create invocation may use its knowledge of the newly created hook; leftover hooks after an uncertain outcome require explicit operator cleanup.

## Verification

For implementation changes, cover ordinary task listing of Gmail renewal, title changes without hook changes, unchanged hook YAML schema, and no hook mutation when a scheduler record is absent or unavailable. Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendScheduledTasksE2eTest'`, then the backend and agent suites specified in [AGENTS.md](../../AGENTS.md). Documentation-only edits require local-link validation and git diff --check.
