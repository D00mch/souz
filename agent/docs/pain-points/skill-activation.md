# Skill activation

## Invariant

Every classic `GraphBasedAgent` turn runs classification, skill activation, then MCP injection. Skill selection reads only user-scoped stored metadata; full bundle content is loaded only for selected skill IDs. Activated instructions are turn-scoped. A successful activation updates dynamic skill command exposure for that context.

Validation cache identity is the user, canonical skill ID, canonical bundle hash, and policy version. A changed bundle invalidates other cached validations for that skill and policy. Changing validation rules requires a new policy version.

Separately tagged `GetSkillByName`, `GetSkillsByCategory`, `GetSkillsNamesByCategory`, `GetKnowledge`, `SearchKnowledge`, and generic `RunSkillCommand` tools are the fixed core tool set for `SkillsGraphBasedAgent`. They remain outside `AgentToolCatalog` and are not connected to the classic `GraphBasedAgent`. The skill tools trust bundles accepted by the registry loader and do not read or write the legacy validation cache. Enabled compiled tools take precedence over stored bundles with the same ID; disabled tools do not hide a stored bundle.

## Why this is fragile

Loading bundles before selection expands the prompt and trust surface. Reusing approval across users, hashes, or policies can execute content that was never approved.

The separately tagged tools merge compiled tools and stored bundles into one ID namespace. Category discovery covers filtered compiled-tool categories only; bundle detail and execution load the current bundle by exact Skill ID. Generic bundle execution must bind the current bundle identity internally before reusing the legacy command implementation.

Blocked and exception paths clear injected skill instructions, and classification resets the active tool list, but those paths do not currently remove a `RunSkillCommand` setup injected by an earlier successful activation from `settings.tools`. Executor lookup can therefore retain stale command capability. Treat this as a known revocation gap, not as a security boundary.

## Safe changes

- Preserve metadata-first selection and reject selector output that is not present in that user's catalog.
- Keep the order structural validation, static validation, then bounded LLM validation. Cache both approvals and rejections for the exact identity.
- Treat a per-skill rejection as local to that skill and continue with other selections. Treat pipeline failures as blocked, clear injected skill instructions, and continue the normal agent turn without skill context.
- Rethrow coroutine cancellation from every phase.
- On successful activation, expose `RunSkillCommand` only when the host provides it and at least one skill is active; otherwise remove the dynamic instance. Inject the active skill identity into calls and use the tool only when an activated instruction requires command or script execution.
- If blocked or failed activation must revoke command execution, fix the blocked/exception branches to remove the managed tool from both settings and active tools, then add a regression test. Do not document revocation as guaranteed until that behavior exists.
- Keep supporting-file content out of selection; load it only as part of the selected bundle's bounded validation and activation path.
- Keep the separately tagged core tools out of `AgentToolCatalog`; the skills-oriented graph owns and isolates its always-available tool set.
- Preserve compiled-tool precedence consistently in summary, detail, and execution paths. Load a stored bundle only after enabled-tool lookup fails.
- Never expose `activeSkills`, bundle hashes, storage paths, or supporting-file content through skill discovery. Generic execution binds those values internally.

## Verification

Run `./gradlew :agent:test` for legacy graph changes. For separately tagged runtime-tool changes, also run `./gradlew :sharedLogic:jvmTest :sharedLogic:compileAndroidMain`.
