# Skill activation

## Invariant

Every agent turn runs classification, skill activation, then MCP injection. Skill selection reads only user-scoped stored metadata; full bundle content is loaded only for selected skill IDs. Activated instructions are turn-scoped. A successful activation updates dynamic skill command exposure for that context.

Validation cache identity is the user, canonical skill ID, canonical bundle hash, and policy version. A changed bundle invalidates other cached validations for that skill and policy. Changing validation rules requires a new policy version.

## Why this is fragile

Loading bundles before selection expands the prompt and trust surface. Reusing approval across users, hashes, or policies can execute content that was never approved.

Blocked and exception paths clear injected skill instructions, and classification resets the active tool list, but those paths do not currently remove a `RunSkillCommand` setup injected by an earlier successful activation from `settings.tools`. Executor lookup can therefore retain stale command capability. Treat this as a known revocation gap, not as a security boundary.

## Safe changes

- Preserve metadata-first selection and reject selector output that is not present in that user's catalog.
- Keep the order structural validation, static validation, then bounded LLM validation. Cache both approvals and rejections for the exact identity.
- Treat a per-skill rejection as local to that skill and continue with other selections. Treat pipeline failures as blocked, clear injected skill instructions, and continue the normal agent turn without skill context.
- Rethrow coroutine cancellation from every phase.
- On successful activation, expose `RunSkillCommand` only when the host provides it and at least one skill is active; otherwise remove the dynamic instance. Inject the active skill identity into calls and use the tool only when an activated instruction requires command or script execution.
- If blocked or failed activation must revoke command execution, fix the blocked/exception branches to remove the managed tool from both settings and active tools, then add a regression test. Do not document revocation as guaranteed until that behavior exists.
- Keep supporting-file content out of selection; load it only as part of the selected bundle's bounded validation and activation path.

## Verification

Run `./gradlew :agent:test`. Cover graph ordering, metadata-only selection, unknown IDs, cache hits and invalidation, mixed approved/rejected selections, cancellation, blocked fail-open behavior, successful dynamic command addition/removal, and blocked or failed activation retaining a prior command setup.
