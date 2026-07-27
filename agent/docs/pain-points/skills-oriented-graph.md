# Skills-oriented graph

## Invariant

`SkillsGraphBasedAgent` exposes exactly `GetSkillByName`, `GetSkillsByCategory`, `GetSkillsNamesByCategory`, `GetKnowledge`, `SearchKnowledge`, and generic `RunSkillCommand`. Its execution boundary replaces both the functions advertised to the model and the executable tool lookup before the graph starts. The effective system message contains compact Skill inventory metadata filtered by the active tool policy and user-scoped registry, while `AgentContext.systemPrompt` remains equal to the caller-provided prompt. It does not run classification or MCP injection.

`NodesSkillInventory` owns Skill inventory prompt augmentation and core-tool restriction. `NodesToolUseWithKnowledge` owns Knowledge-aware tool-result handling. `NodesCommon` owns generic tool-call execution and the inline-only tool-use node.

Tool results larger than 8,192 UTF-8 bytes are stored in conversation-scoped Knowledge and replaced with a compact JSON reference. A result of exactly 8,192 bytes stays inline. Skill-discovery, `GetKnowledge`, and `SearchKnowledge` results are always returned inline. Storage unavailability and persistence failures keep the original result inline; coroutine cancellation propagates.

Knowledge entries use UTF-16 offsets and lengths because they retain Kotlin strings. `GetKnowledge` returns all retained content: a complete read returns its text, while a truncated read exposes the retained head and tail with their original ranges and the omitted range. `SearchKnowledge` searches retained segments independently, so it never matches across an omitted middle. A match without additional context omits its redundant excerpt and excerpt offsets. Knowledge is temporary and belongs to the exact tool-invocation conversation scope.

## Why this is fragile

Advertising a small tool list without replacing executable lookup would let a fabricated call reach a catalog tool. Offloading skill discovery would replace model-consumable schemas and instructions with avoidable Knowledge references. Re-offloading Knowledge reads could create an endless chain of references. Measuring the boundary in Kotlin characters would incorrectly treat multibyte UTF-8 results.

## Safe changes

- Keep core-tool restriction at the execution boundary so every graph node sees the restricted context; tool loops return directly to the LLM.
- Keep `AgentContext.systemPrompt` equal to the configured prompt. Let `NodesSkillInventory` capture filtered tool-backed Skill IDs and user-scoped stored Skill metadata per turn and append them only to the effective system message in history.
- Keep memory recall after history input and before context enrichment. Run it only once per user turn.
- Keep completed-turn memory capture in the graph's finalization node so failed finalization does not schedule capture.
- Keep large-result processing in `NodesToolUseWithKnowledge`; `NodesCommon.toolUse()` remains inline-only.
- Preserve function-result role, name, attachments, and call ID when replacing only its content.
- Keep Knowledge cleanup tied to destructive or local conversation-close lifecycles. Backend archive is non-destructive and does not clear Knowledge.

## Verification

Run `./gradlew :agent:test`. Cover the 8,192/8,193-byte boundary with multibyte input, core-tool isolation, skill-discovery and Knowledge-read exemptions, repeated tool loops, storage failure, and cancellation.
