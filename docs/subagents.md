# Subagents

`SpawnSubagent` starts a self-contained task and returns `{"subagentId":"..."}`. The parent can continue working while multiple children run. The child receives a fresh history, selected capabilities, and a final-answer instruction. It cannot delegate further. Desktop and backend use the same child graph.

```json
{
  "task": "Calculate the total cost of 12 items at 19.50 each, then add 8 for delivery. Return the calculation and total.",
  "skillIds": ["Calculator"],
  "maxTurns": 8
}
```

`skillIds` contains enabled compiled-tool names or file-backed Skill IDs. Compiled tools are exposed directly, except backend client Skills: they use `RunSkillCommand` with their full schemas and channel-routing guidance in the child prompt. Selected file-backed instructions are loaded through the host's approval policy, and their bundled commands are available through a restricted `RunSkillCommand`. Missing or disabled selections fail the call; an empty list gives the child no tools.

File-backed Skills use the same command execution as the parent, with approval applied when selected at spawn. Commands use the existing Skill directory, so executable permissions, file edits, and generated outputs persist between calls.

JVM plugin tools use `plugin.<pluginId>.<toolId>` names and inherit the parent's exact version bindings.
Only selected tools enter the child catalog; a rollout or user-settings change does not expand it.
Operator disable or quarantine still blocks subsequent calls.

[Composite commands](composite-skill-commands.md) execute fixed tool/script/wait sequences in one call. Include every tool used by their steps in `skillIds` alongside the file-backed Skill; its manifest does not grant capabilities.

The optional `model` selects an exact advertised model ID; omission inherits the parent's actual model and provider. Temperature and context size are inherited. `maxTurns` defaults to 32 and accepts 1–128 model turns. Each parent execution allows eight active children and 32 total spawns; cancellation cleanup counts as active. Further spawns return `subagent_concurrency_limit` (wait for capacity) or `subagent_task_limit` (execution-wide limit). Each child has a cooperative deadline configured by the host, defaulting to ten minutes. Expiry returns `failed` with `error.code: "subagent_timeout"` and `sideEffectsMayHaveOccurred: true` after cleanup. Existing provider retries and timeouts still apply.

Retained outcomes are limited to 64 KiB of UTF-8 JSON per child, or at most 2 MiB across 32 children. Oversized answers or errors are replaced with `failed`, `error.code: "subagent_result_limit"`, `resultBytes`, and `sideEffectsMayHaveOccurred: true`. The oversized payload and its files are not retained or returned. Inspect current state before retrying: child work may already have completed its side effects.

The optional `reasoningEffort` accepts `minimal`, `low`, `medium`, or `high` and applies only to the child. For example, add `"reasoningEffort": "low"` for a mostly mechanical task. Omission leaves effort unspecified for provider defaults, even if the parent has an explicit effort. Support depends on the selected provider and model.

Spawn validation failures return `{"error":{"code":"...","message":"..."}}` without creating a child. After a successful spawn, use these parent-only tools with its `subagentId`:

| Tool | Behavior |
| --- | --- |
| `WaitSubagent` | Waits up to `timeoutMs` (default 30,000; range 0–30,000). Zero polls; timeout does not cancel the child. |
| `CancelSubagent` | Requests cancellation of one child and returns `cancelling` while cleanup runs, then `cancelled`. Repeated cancellation is harmless. |

Both return `subagentId` and `status`: `running`, `cancelling`, `completed`, `failed`, or `cancelled`. Completed results include `result`; failures include `error`. Reads do not consume results. A completed or failed child keeps its outcome when cancelled afterward. Unknown IDs return `subagent_not_found`; IDs are usable only in their owning parent execution.

For example, call `WaitSubagent` with `{"subagentId":"<returned ID>","timeoutMs":30000}`, or set `timeoutMs` to `0` to inspect status without waiting. A completed answer looks like `{"subagentId":"...","status":"completed","result":"242"}`. If still `running`, continue other work or wait again; do not spawn the same task again just to retrieve its result. When no other work remains, use the full wait instead of frequent short polls: each repeated tool call requires another parent-model turn.

Turn exhaustion returns `status: "failed"`, the `subagent_turn_limit` error, and `progress`: `modelTurns`, `sideEffectsMayHaveOccurred`, `completedToolCallCount`, `omittedToolCallCount`, and bounded recent `completedToolCalls`. Each reported call contains `toolCallId`, `name`, `result`, and a `truncated` flag; attachments and child reasoning are omitted. Counts describe returned tool messages, which can include tool-level errors, rather than successful operations.

Tools requested on the last allowed model turn still execute. Exhaustion does not undo their effects, and the warning remains conservative even when no results are available. Inspect the reported results and current state before retrying to avoid repeating completed work. Cancellation is cooperative and does not roll back side effects. Wait until `cancelled` before depending on resources released by child cleanup. Cancelling a child does not send a stop command to an external device; an already dispatched operation may continue. Child-only cancellation leaves the parent and siblings runnable. Queued input can steer the parent while children work; a current bounded wait completes before queued input is applied.

Completed, failed, and cancelled results stay in memory until the parent execution ends. Every parent exit—success, failure, or cancellation—cancels unfinished children, joins cleanup, and releases the registry. Blocking or cancellation-ignoring operations can delay this exit. Collect required results before the parent's final answer. Children do not survive a new turn, restart, or option continuation. Parent and child client calls use independent correlated waiters, including calls in the same thread; cancellation removes only the selected child's waiters.

## Compatibility and migration

The blocking `SpawnSubagent` contract is unsupported: consumers must read `subagentId`, then use `WaitSubagent` to obtain the answer. Update Skill instructions that expect a result directly from spawn. Turn-limit consumers must recognize `failed` plus `error.code == "subagent_turn_limit"`; the bounded progress fields retain their meanings. There is no blocking compatibility mode or backend child-cancellation endpoint.

Hosts install all three tools with `SubagentToolFactory.create(settings).tools` through `AgentCoreTools.subagentTools`. Standard agent graphs own the lifecycle automatically. Direct tool hosts wrap the entire parent loop in `withSubagentTasks`; wrapping just a spawn call would cancel its child on return.

## Deadline configuration

Desktop uses the shared ten-minute deadline. Host integrations can override `AgentSettingsProvider.subagentTaskTimeout`; standard desktop DI and the backend execution kernel pass that value to each graph run.

`SUBAGENT_TASK_TIMEOUT_MS` sets the cooperative execution deadline for each child in milliseconds (default `600000`, ten minutes). For example, `SUBAGENT_TASK_TIMEOUT_MS=1200000` allows twenty minutes. The backend reads the environment variable before the same-named JVM system property and validates it at startup: values must be positive integers representing finite Kotlin durations; blank, malformed, zero, negative, and overflowing values are rejected. Restart after changing it. This server-wide setting is independent of provider request timeouts and `WaitSubagent.timeoutMs`; users and tool arguments cannot override it.

## Model configuration

`SUBAGENT_MODELS_JSON` adds model IDs grouped by provider. For example, launch either host with:

```sh
export SUBAGENT_MODELS_JSON='{"OPENAI":["my-custom-model","my-deployment/v2"],"ANTHROPIC":["my-anthropic-model"]}'
```

The tool advertises the configured IDs plus the parent's current model. Without the setting, with `{}`, or with empty provider lists, only the parent model is available. Unlisted providers contribute no additional models. The parent model is always allowed; if its ID is configured under another provider, the parent's provider takes precedence for that execution. A custom OpenAI parent contributes its actual `OPENAI_MODEL` deployment ID.

The host selects the provider API and existing credentials; the tool accepts no provider argument. Children can use a different provider from the parent. Model IDs are case-sensitive, preserved unchanged, and need not appear in `LLMModel`. An unadvertised ID returns `subagent_model_unavailable` before child execution. Configuration authorizes a choice; the provider still checks whether that model exists and is accessible. Local inference requires an installed, supported model profile. Local parent and child model calls queue on the shared native runtime; cancelling one call waits for its bridge cleanup before another uses that runtime.

Configuration is validated when host settings initialize. Malformed JSON, unknown or host-unsupported providers (including Giga on backend), blank IDs, and IDs assigned to multiple providers are errors. Repeated IDs within one provider are deduplicated. Desktop lookup uses stored preferences, then environment, then JVM system properties; backend uses environment, then JVM system properties. Restart the host after changing this setting. Each parent execution uses the same choice snapshot for its schema and validation.

## Delegating from a Skill

A Skill uses ordinary instructions without extra manifest fields. For example:

```markdown
---
name: Check a purchase total
description: Calculate an item total including delivery.
---

Call SpawnSubagent with the user's quantities, prices, and delivery cost
included in task, skillIds set to ["Calculator"], and maxTurns set to 8.
Save subagentId and call WaitSubagent with timeoutMs: 30000 until terminal.
Use the completed result to answer the user. If delegation fails, explain
the failure or complete the calculation using an available tool.
```

Pass every needed detail in `task`: children do not inherit the parent's conversation, memory, or environment enrichment. Include all required tools and file-backed Skills explicitly; a Skill's instructions do not grant additional capabilities.

The existing tool activity indicates delegation. Child intermediate text and graph events do not become parent chat output. Tools retain the host's normal permission and client-interaction behavior. Backend recovery and durable child execution records are outside this execution model.

## Desktop manual test

[folder-brief](skills/folder-brief/SKILL.md) delegates a local notes brief to one child with only `ListFiles` and `ExtractTextFromFile`, capped at eight model turns. Enable both tools in desktop settings.

For the default local sandbox, install the Skill from the repository root:

```sh
mkdir -p ~/.local/state/souz/skills/folder-brief
cp -n docs/skills/folder-brief/SKILL.md ~/.local/state/souz/skills/folder-brief/SKILL.md
```

Start a fresh desktop turn and ask: "Use the folder-brief Skill to brief REPO/docs/skills/folder-brief/sample-notes. Focus on open actions and conflicting dates." Replace `REPO` with the absolute checkout path. The [sample notes](skills/folder-brief/sample-notes/01-meeting.md) and [handoff](skills/folder-brief/sample-notes/02-handoff.md) contain three actions (Mia, Anton, Lina) and conflicting launch dates (September 18 and 21, 2026); approved budget is not an open action.

Check that the parent invokes `SpawnSubagent` with exactly those two Skill IDs, collects the result with `WaitSubagent`, and returns a brief citing both files. For trace-level verification, child requests must advertise only those two tools; the final text alone does not prove delegation. Disabling `ExtractTextFromFile` and repeating in a fresh turn should produce `skill_disabled` without the parent doing the reading itself. Restore the setting after this check.
