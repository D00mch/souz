# Skills-oriented graph

## Invariant

`SkillsGraphBasedAgent` exposes exactly `GetSkills`, `GetKnowledge`, and generic `RunSkillCommand`. After history input and memory recall, it replaces both the functions advertised to the model and the executable tool lookup with those three tools. It does not run classification, legacy skill activation, or MCP injection.

Tool results larger than 4,096 UTF-8 bytes are stored in conversation-scoped Knowledge and replaced with a compact JSON reference. A result of exactly 4,096 bytes stays inline. `GetKnowledge` results are never offloaded again. Storage unavailability and persistence failures keep the original result inline; coroutine cancellation propagates.

Knowledge entries use UTF-16 offsets and lengths because they retain Kotlin strings. A complete read returns all retained text. A truncated read exposes the retained head and tail with their original ranges and the omitted range. Regex retrieval searches retained segments independently, so it never matches across an omitted middle. Knowledge is temporary and belongs to the exact tool-invocation conversation scope.

## Why this is fragile

Advertising a small tool list without replacing executable lookup would let a fabricated call reach a catalog tool. Re-offloading Knowledge reads could create an endless chain of references. Measuring the boundary in Kotlin characters would incorrectly treat multibyte UTF-8 results.

## Safe changes

- Keep memory recall before core-tool installation, and keep core-tool installation before context enrichment. Run both only once per user turn; tool loops return directly to the LLM.
- Keep completed-turn memory capture in the graph's finalization node so failed finalization does not schedule capture.
- Keep large-result processing opt-in beside the classic `NodesCommon.toolUse()` path.
- Preserve function-result role, name, attachments, and call ID when replacing only its content.
- Keep Knowledge cleanup tied to destructive or local conversation-close lifecycles. Backend archive is non-destructive and does not clear Knowledge.

## Verification

Run `./gradlew :agent:test`. Cover the 4,096/4,097-byte boundary with multibyte input, core-tool isolation, repeated tool loops, Knowledge-read exemption, storage failure, and cancellation.
