# Skills-oriented graph

## Invariant

The [graph](../src/main/kotlin/ru/souz/SkillsGraphBasedAgent.kt) replaces advertised schemas and executable lookup with host-supplied [core tools](../src/main/kotlin/ru/souz/agent/AgentCoreTools.kt) before execution. Leaving catalog entries in executable lookup would let a fabricated call bypass scoped Skill invocation. Keep core tools outside the catalog and preserve duplicate-name rejection and selected categories when restricting tools.

[Skill inventory](skill-activation.md) augments the effective system message once per turn. Keep `AgentContext.systemPrompt` equal to the configured prompt. Tool loops and mid-run steering return directly to the model. See [execution lifecycle](execution-lifecycle.md) for continuation and memory ordering.

### Context budget and compaction

All conversational routes, including child calls, share the configured `CONTEXT_SIZE`. Conversational and summary inputs may use up to 80% of that budget. Count prepared message text, tool arguments and schemas without transport escaping. Provider output caps of 4,096 conversation tokens and 1,024 summary tokens are independent. [Request preparation](../src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt)

Snapshot ephemeral host instructions once per conversational call and reuse them after compaction. Keep them out of stored history, classification and summary requests. Render file references as text only in provider requests and carry a host-bounded set of recent structured references onto the summary. [Attachment contracts](../../backend/docs/attachments.md#history-and-persistence)

[Compaction](../src/main/kotlin/ru/souz/agent/nodes/NodesSummarization.kt) summarizes the completed prefix in one request and preserves system messages and the entire active exchange, including memory, host context, tool exchanges and mid-run inputs. Track the boundary through history edits in execution state only. If the full prefix and summary prompt cannot fit together, keep history intact and reject the oversized request. Reject oversized requests with no compactable history or that still exceed budget after rebuilding. Empty and length-truncated summaries fail compaction.

### Tool-result offloading

[Knowledge-aware tool execution](../src/main/kotlin/ru/souz/agent/nodes/NodesToolUseWithKnowledge.kt) replaces result content above 8,192 UTF-8 bytes with a conversation-scoped reference. Skill discovery, host-supplied discovery and Knowledge reads stay inline to keep schemas available and prevent recursive references. `SearchMemory` follows the size limit. Missing scope, unavailable storage and write failures keep the original content inline. Propagate cancellation. Plain tool loops keep results inline.

Preserve result identity and structured attachments when replacing content. Apply reply-file changes in tool-call order before offloading and reject changes beyond the reply limits. [Reply selection](../../backend/docs/attachments.md#tools-and-selection) · [Knowledge retention and search](../../sharedLogic/docs/runtime-sandbox-and-skills.md#invariants)

## Verification

Run `./gradlew :agent:test` for runtime changes. See [tool isolation](../src/test/kotlin/ru/souz/SkillsGraphBasedAgentTest.kt), [compaction and steering](../src/test/kotlin/ru/souz/agent/nodes/NodesLLMTest.kt), and [offload boundaries and failures](../src/test/kotlin/ru/souz/agent/nodes/NodesToolUseWithKnowledgeTest.kt).
