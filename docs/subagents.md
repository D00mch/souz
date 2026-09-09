# Subagents

`SpawnSubagent` delegates a self-contained task and suspends the parent until the child returns. The child receives a fresh history, selected capabilities, and a final-answer instruction. It cannot delegate further. Desktop and backend use the same child graph.

```json
{
  "task": "Calculate the total cost of 12 items at 19.50 each, then add 8 for delivery. Return the calculation and total.",
  "skillIds": ["Calculator"],
  "maxTurns": 8
}
```

`skillIds` contains enabled compiled-tool names or file-backed Skill IDs. Compiled tools are exposed directly. Selected file-backed instructions are loaded through the host's approval policy, and their bundled commands are available through a restricted `RunSkillCommand`. Missing or disabled selections fail the call; an empty list gives the child no tools.

File-backed Skills use the same command execution as the parent, with approval applied when selected at spawn. Commands use the existing Skill directory, so executable permissions, file edits, and generated outputs persist between calls.

The optional `model` selects an available model within the parent's provider; omission inherits the parent model. Temperature and context size are inherited. `maxTurns` defaults to 32 and accepts 1–128 model turns. Existing provider retries and timeouts still apply.

The parent receives `{"result":"..."}` or `{"error":{"code":"...","message":"..."}}`. Reaching the model-turn limit returns `subagent_turn_limit`. The parent can recover from failures. Cancelling the parent cancels the child; queued parent input waits for the current child to finish.

## Delegating from a Skill

A Skill uses ordinary instructions without extra manifest fields. For example:

```markdown
---
name: Check a purchase total
description: Calculate an item total including delivery.
---

Call SpawnSubagent with the user's quantities, prices, and delivery cost
included in task, skillIds set to ["Calculator"], and maxTurns set to 8.
Use the child's result to answer the user. If delegation fails, explain
the failure or complete the calculation using an available tool.
```

Pass every needed detail in `task`: children do not inherit the parent's conversation, memory, or environment enrichment. Include all required tools and file-backed Skills explicitly; a Skill's instructions do not grant additional capabilities.

The existing tool activity indicates delegation. Child intermediate text and graph events do not become parent chat output. Tools retain the host's normal permission and client-interaction behavior. Backend recovery and durable child execution records are outside this execution model.

## Desktop manual test

[folder-brief](skills/folder-brief/SKILL.md) delegates a local notes brief to one child with only `ListFiles` and `ReadFile`, capped at eight model turns. Enable both tools in desktop settings.

For the default local sandbox, install the Skill from the repository root:

```sh
mkdir -p ~/.local/state/souz/skills/folder-brief
cp -n docs/skills/folder-brief/SKILL.md ~/.local/state/souz/skills/folder-brief/SKILL.md
```

Start a fresh desktop turn and ask: "Use the folder-brief Skill to brief REPO/docs/skills/folder-brief/sample-notes. Focus on open actions and conflicting dates." Replace `REPO` with the absolute checkout path. The [sample notes](skills/folder-brief/sample-notes/01-meeting.md) and [handoff](skills/folder-brief/sample-notes/02-handoff.md) contain three actions (Mia, Anton, Lina) and conflicting launch dates (September 18 and 21, 2026); approved budget is not an open action.

Check that the parent invokes `SpawnSubagent` with exactly those two Skill IDs and resumes with a brief citing both files. For trace-level verification, child requests must advertise only those two tools; the final text alone does not prove delegation. Disabling `ReadFile` and repeating in a fresh turn should produce `skill_disabled` without the parent doing the reading itself. Restore the setting after this check.
