# Skill inventory and approval

## Invariant

[Inventory](../src/main/kotlin/ru/souz/agent/nodes/NodesSkillInventory.kt) runs after memory recall and before host context enrichment. List filtered compiled tools and user-scoped file-backed IDs. Treat IDs and selected descriptions as escaped discovery data. Select descriptions from bounded recent dialogue and the current request using host classifiers with execution-LLM fallback. Keep ID-only discovery if selection fails. Preserve classification of confirmations and fragments. [Read-only inventory](../../sharedLogic/docs/runtime-sandbox-and-skills.md#invariants)

Execution-bound server discovery may preload selected servers before reading the live catalog. Keep every server discoverable, bound the loaded-tool hints, and preserve the [core-tool boundary](skills-oriented-graph.md). [MCP discovery](../../backend/docs/mcp.md#discovery)

Load full bundles by exact ID for detail, invocation and explicit subagent selection. Enabled compiled tools win collisions, and disabled tools leave same-ID bundles discoverable. Category discovery lists only filtered compiled tools. Keep bundle identity, paths and supporting files internal, and scope selected child bundles to that invocation. [Shared resolver](../../sharedLogic/src/main/kotlin/ru/souz/tool/skills/SkillResolver.kt) · [Subagent execution](subagents.md)

## Safe changes

When configured, [approval](../src/main/kotlin/ru/souz/agent/skills/validation/README.md) must succeed before exposing `SKILL.md`, executing bundled commands or preparing a child prompt. Keep rejection local to the requested Skill and propagate cancellation. The backend uses ungated classpath and sandbox bundles.

Keep [composite command](../../docs/composite-skill-commands.md) manifest validation and runtime template resolution aligned so accepted references resolve the same way at execution.

## Verification

Run `./gradlew :agent:test :sharedLogic:test` for runtime changes. See [inventory tests](../src/test/kotlin/ru/souz/agent/nodes/NodesSkillInventoryTest.kt), [approval tests](../src/test/kotlin/ru/souz/agent/skills/SkillApprovalGateTest.kt), and [Skill tool tests](../../sharedLogic/src/test/kotlin/ru/souz/tool/skills/SkillRuntimeToolsTest.kt).
