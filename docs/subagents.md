# Subagents

`SpawnSubagent` starts a self-contained task and returns `{"subagentId":"..."}`. The parent can continue working while multiple children run. The child receives a fresh history, selected capabilities, and a final-answer instruction. It cannot delegate further. The backend creates a separate child graph for each invocation.

```json
{
  "task": "Calculate the total cost of 12 items at 19.50 each, then add 8 for delivery. Return the calculation and total.",
  "skillIds": ["Calculator"],
  "maxTurns": 8
}
```

`skillIds` contains enabled compiled-tool names or file-backed Skill IDs. Compiled tools are exposed directly, except backend client Skills: they use `RunSkillCommand` with their full schemas and channel-routing guidance in the child prompt. Selected file-backed instructions are loaded through the host's approval policy, and their bundled commands are available through a restricted `RunSkillCommand`. Missing or disabled selections fail the call; an empty list gives the child no tools.

File-backed Skills use the same command execution as the parent, with approval applied when selected at spawn. Commands use the existing Skill directory, so executable permissions, file edits, and generated outputs persist between calls.

[Composite commands](composite-skill-commands.md) execute fixed tool/script/wait sequences in one call. Include every tool used by their steps in `skillIds` alongside the file-backed Skill; its manifest does not grant capabilities.

The optional `model` selects an exact advertised model ID; omission inherits the parent's actual model and provider. Temperature and context size are inherited. `maxTurns` defaults to 32 and accepts 1–128 model turns. Existing provider retries and timeouts still apply.

The optional `reasoningEffort` accepts `minimal`, `low`, `medium`, or `high` and applies only to the child. For example, add `"reasoningEffort": "low"` for a mostly mechanical task. Omission leaves effort unspecified for provider defaults, even if the parent has an explicit effort. Support depends on the selected provider and model.

Spawn validation failures return `{"error":{"code":"...","message":"..."}}` without creating a child. After a successful spawn, use these parent-only tools with its `subagentId`:

| Tool | Behavior |
| --- | --- |
| `GetSubagentStatus` | Returns the current status without waiting. |
| `WaitSubagent` | Waits up to `timeoutMs` (default 1,000; range 0–30,000). Zero polls; timeout does not cancel the child. |
| `CancelSubagent` | Requests cancellation of one child and returns its status. Repeated cancellation is harmless. |

All three return `subagentId` and `status`: `running`, `completed`, `failed`, or `cancelled`. Completed results include `result`; failures include `error`. Reads do not consume results. A completed or failed child keeps its outcome when cancelled afterward. Unknown IDs return `subagent_not_found`; IDs are usable only in their owning parent execution.

For example, call `WaitSubagent` with `{"subagentId":"<returned ID>","timeoutMs":1000}`. A completed answer looks like `{"subagentId":"...","status":"completed","result":"242"}`. If still `running`, continue other work or wait again; do not spawn the same task again just to retrieve its result.

Turn exhaustion returns `status: "failed"`, the `subagent_turn_limit` error, and `progress`: `modelTurns`, `sideEffectsMayHaveOccurred`, `completedToolCallCount`, `omittedToolCallCount`, and bounded recent `completedToolCalls`. Each reported call contains `toolCallId`, `name`, `result`, and a `truncated` flag; attachments and child reasoning are omitted. Counts describe returned tool messages, which can include tool-level errors, rather than successful operations.

Tools requested on the last allowed model turn still execute. Exhaustion does not undo their effects, and the warning remains conservative even when no results are available. Inspect the reported results and current state before retrying to avoid repeating completed work. Cancellation is cooperative and does not roll back side effects. A `cancelled` response acknowledges the request; cleanup may still be running. Child-only cancellation leaves the parent and siblings runnable. Queued input can steer the parent while children work; a current bounded wait completes before queued input is applied.

Completed, failed, and cancelled results stay in memory until the parent execution ends. Every parent exit—success, failure, or cancellation—cancels unfinished children, joins cleanup, and releases the registry. Collect required results before the parent's final answer. Children do not survive a new turn, restart, or option continuation. Client tools retain their existing host concurrency limits, including one outstanding same-thread client-tool waiter.

## Compatibility and migration

The blocking `SpawnSubagent` contract is unsupported: consumers must read `subagentId`, then use `WaitSubagent` or `GetSubagentStatus` to obtain the answer. Update Skill instructions that expect a result directly from spawn. Turn-limit consumers must recognize `failed` plus `error.code == "subagent_turn_limit"`; the bounded progress fields retain their meanings. There is no blocking compatibility mode or backend child-cancellation endpoint.

Hosts install all four tools with `SubagentToolFactory.create(settings).tools` through `AgentCoreTools.subagentTools`. Standard agent graphs own the lifecycle automatically. Direct tool hosts wrap the entire parent loop in `withSubagentTasks`; wrapping just a spawn call would cancel its child on return.

## Model configuration

`SUBAGENT_MODELS_JSON` adds model IDs grouped by provider. For example, configure the backend with:

```sh
export SUBAGENT_MODELS_JSON='{"OPENAI":["my-custom-model","my-deployment/v2"],"ANTHROPIC":["my-anthropic-model"]}'
```

The tool advertises the configured IDs plus the parent's current model. Without the setting, with `{}`, or with empty provider lists, only the parent model is available. Unlisted providers contribute no additional models. The parent model is always allowed; if its ID is configured under another provider, the parent's provider takes precedence for that execution. A custom OpenAI parent contributes its actual `OPENAI_MODEL` deployment ID.

The host selects the provider API and existing credentials; the tool accepts no provider argument. Children can use a different provider from the parent. Model IDs are case-sensitive, preserved unchanged, and need not appear in `LLMModel`. An unadvertised ID returns `subagent_model_unavailable` before child execution. Configuration authorizes a choice; the provider still checks whether that model exists and is accessible.

Configuration is validated when host settings initialize. Malformed JSON, unknown or host-unsupported providers (including Giga on backend), blank IDs, and IDs assigned to multiple providers are errors. Repeated IDs within one provider are deduplicated. The backend reads environment variables, then JVM system properties. Restart it after changing this setting. Each parent execution uses the same choice snapshot for its schema and validation.

## Delegating from a Skill

A Skill uses ordinary instructions without extra manifest fields. For example:

```markdown
---
name: Check a purchase total
description: Calculate an item total including delivery.
---

Call SpawnSubagent with the user's quantities, prices, and delivery cost
included in task, skillIds set to ["Calculator"], and maxTurns set to 8.
Save subagentId and call WaitSubagent until its status is terminal.
Use the completed result to answer the user. If delegation fails, explain
the failure or complete the calculation using an available tool.
```

Pass every needed detail in `task`: children do not inherit the parent's conversation, memory, or environment enrichment. Include all required tools and file-backed Skills explicitly; a Skill's instructions do not grant additional capabilities.

The existing tool activity indicates delegation. Child intermediate text and graph events do not become parent chat output. Tools retain the host's normal permission and client-interaction behavior. Backend recovery and durable child execution records are outside this execution model.

## Example Skill

[folder-brief](skills/folder-brief/SKILL.md) delegates a notes brief to one child with only `ListFiles` and `ExtractTextFromFile`, capped at eight model turns. Install the Skill in the user's sandbox registry and enable both compiled tools for the parent execution.

Ask the backend to use it on the sandbox-visible notes folder. The [sample notes](skills/folder-brief/sample-notes/01-meeting.md) and [handoff](skills/folder-brief/sample-notes/02-handoff.md) contain three actions (Mia, Anton, Lina) and conflicting launch dates (September 18 and 21, 2026); approved budget is not an open action.

Verify that the parent invokes `SpawnSubagent` with exactly those two Skill IDs and resumes with a brief citing both files. Child requests must advertise only those tools; the final text alone does not prove delegation. Disabling `ExtractTextFromFile` and repeating in a fresh turn should produce `skill_disabled`.
