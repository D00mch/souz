# Skills-oriented graph

## Invariant

`SkillsGraphBasedAgent` exposes `GetSkillByName`, `GetSkillsByCategory`, `GetSkillsNamesByCategory`, `GetKnowledge`, `SearchKnowledge`, `SearchMemory`, and generic `RunSkillCommand`. `GraphBasedAgent` exposes the universal core subset `GetSkillByName`, `GetKnowledge`, `SearchKnowledge`, `SearchMemory`, and generic `RunSkillCommand` independently of classification. Both install the host's execution-bound `SpawnSubagent` when supplied through `AgentCoreTools`. The skills graph replaces advertised and executable tools before execution. Its effective system message contains filtered tool-backed IDs, user-scoped escaped file-backed IDs, and concise descriptions beside relevant exact IDs. `AgentContext.systemPrompt` remains the caller-provided prompt. Inventory selects descriptions with Jev and execution-LLM fallback; direct-tool classification and MCP injection are omitted.

Automatically routed simple replies expose no tools, clear previous inventory and host context, and skip Skill inventory and enrichment. Turn classification runs before recall; complexity and recall are independent. See [model routing](execution-lifecycle.md#model-routing).

Continuation ordering, reserved publication, response acceptance, and stream revisions follow [Execution lifecycle](execution-lifecycle.md).

`NodesLLM` prepares every conversational call, including tool-loop and subagent calls without personality.
Hosts may supply `conversationInstructions` to add ephemeral system instructions. The agent reads one
snapshot per call and reuses it after compaction; the next call reads fresh instructions.
The prepared message text, tool arguments and schemas share the context estimate;
transport JSON escaping does not increase the text estimate.
Conversational and summary input must fit 80% of the configured `CONTEXT_SIZE`, shared by all models.
Compaction runs before a conversational call when the prepared input exceeds that limit, and the rebuilt
request is checked against the same limit. Exactly 80% fits. `maxTokens` only limits provider output
(4,096 tokens for conversation, 1,024 for summaries); it does not change the input budget.

Compaction sends the completed prefix before `AgentContext.activeExchangeStartIndex`, followed by the
summarization prompt, in one call. Both must fit the same 80% limit. System instructions and the entire
active exchange, including fresh memory, host context, tools, and mid-run inputs, stay intact.
History is neither split nor dropped to fit the summary
request; if it cannot fit, history stays unchanged and the conversational request is rejected.
Requests without compactable history or still over the limit after compaction are also rejected.
Empty or length-truncated summaries fail compaction. Skill selection and summaries use the ordinary
`llmApi` without host augmentation.
Merging instructions leaves stored context unchanged. Turn finalization converts the accepted answer
and schedules memory capture; it does not summarize history.

`NodesSkillInventory` owns Skill inventory prompt augmentation. `AgentContext.withOnlyTools` replaces advertised and executable tools at the skills-graph boundary through the list-based `AgentTools` factory shared with subagent selection. This factory rejects duplicate names and retains only explicitly supplied categories for selected tools. `SteerableChatNode` owns execution-scoped continuation boundaries. `NodesCommon` enriches history with host context. `NodesPlain` is a stateless object providing history, response, and tool-execution helpers. `NodesToolUseWithKnowledge` composes tool execution with Knowledge-aware result handling.

Tool results larger than 8,192 UTF-8 bytes are stored in conversation-scoped Knowledge and replaced with a compact JSON reference. A result of exactly 8,192 bytes stays inline. Skill-discovery, `GetKnowledge`, and `SearchKnowledge` results are always returned inline. `SearchMemory` has no always-inline exemption and a large result may be offloaded. Storage unavailability and persistence failures keep the original result inline; coroutine cancellation propagates.

Knowledge entries use UTF-16 offsets and lengths because they retain Kotlin strings. `GetKnowledge` returns all retained content: a complete read returns its text, while a truncated read exposes the retained head and tail with their original ranges and the omitted range. `SearchKnowledge` searches retained segments independently, so it never matches across an omitted middle. A match without additional context omits its redundant excerpt and excerpt offsets. Knowledge is temporary and belongs to the exact tool-invocation conversation scope.

## Why this is fragile

Advertising a small tool list without replacing executable lookup would let a fabricated call reach a catalog tool. Offloading skill discovery would replace model-consumable schemas and instructions with avoidable Knowledge references. Re-offloading Knowledge reads could create an endless chain of references. Measuring the boundary in Kotlin characters would incorrectly treat multibyte UTF-8 results.

## Safe changes

- Keep core-tool restriction at the execution boundary so every graph node sees the restricted context; tool loops return directly to the LLM.
- Keep `AgentContext.systemPrompt` equal to the configured prompt. `NodesSkillInventory` reloads IDs and selects descriptions per turn, after memory recall; selections live only in the effective system message. Mid-run steering returns to the LLM without repeating discovery.
- Keep memory recall after initial turn classification and before context enrichment; remove stale memory even when recall is skipped. Run it only once per user turn.
- Keep completed-turn memory capture in the graph's finalization node so failed finalization does not schedule capture.
- Keep large-result processing in `NodesToolUseWithKnowledge`; `NodesPlain.toolUse()` remains inline-only.
- Preserve function-result role, name, attachments, and call ID when replacing only its content.
- Keep Knowledge cleanup tied to destructive or local conversation-close lifecycles. Backend archive is non-destructive and does not clear Knowledge.

## Verification

Run `./gradlew :agent:test`. Cover the 8,192/8,193-byte boundary with multibyte input, core-tool isolation, skill-discovery and Knowledge-read exemptions, repeated tool loops, storage failure, and cancellation.
