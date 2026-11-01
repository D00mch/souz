---
name: skill-authoring
description: Create, test, and edit the user's personal Souz skills in their Docker sandbox. Use when the user asks to create a skill, save a repeatable workflow as a skill, or repair or extend a personal skill. Not for changing built-in tools or system skills.
---

# Personal skill authoring

Work in the invoking user's Docker sandbox. Personal skills are ordinary folders
under `/souz/state/skills/<skill-id>/`; no backend restart or registration is needed.
Use this workflow only in Docker, where `/souz/workspace` and `/souz/state/skills`
exist. Do not translate these paths to the desktop host or another user's container.

## Tools and execution

Load skills with `GetSkillByName`. Run commands with `RunSkillCommand`, using
`skillId: "skill-authoring"` and `arguments: {runtime: "PYTHON", script: "..."}`
for inspection, file authoring, and checks. Python's standard library is sufficient
for filesystem operations; separate `NewFile`, `ReadFile`, or `EditFile` tools are
not required. Respect explicit user restrictions on files and actions.

The command starts in this skill's directory. Use absolute paths for the target
skill and scratch files. `workingDirectory` and `scriptPath` must stay inside the
selected skill; to test a draft elsewhere, use an inline script with an explicit
path. Keep this authoring skill unchanged while working on a user's skill.

Use the interpreter already available: Python 3, Bash, or Node. Check any extra
library before relying on it; do not change the container's global dependencies.
Use stdin or argv for user data instead of interpolating it into shell code.
Commands default to 60 seconds and are capped at 300 seconds.

## Solve the task, then capture the method

A skill is a reusable way to produce the user's result. A valid folder, a working
helper, or a description of unsuccessful attempts is not that result. First find
and execute a working method; then package it so another conversation can repeat it.
For a simple known transformation these steps may be short. For an unfamiliar
integration, discovery and experiments are part of the authoring task.

1. Define the acceptance case from the user's request: real input, observable
   output, and how to verify that output independently. Usability is part of it:
   an ordinary request should not require internal IDs or redundant confirmations.
   Multiple search hits do not alone prove ambiguity: compare the requested entity
   type, name and supplied qualifiers with the returned evidence. Ask only when
   materially different candidates still satisfy the request.
   Keep the whole requested
   outcome in scope while developing individual pieces. Use existing authorization;
   ask only for genuinely missing requirements or access.
2. Inspect the available tools, existing skills, runtime libraries, documented
   interfaces, and relevant private configuration. Distinguish unavailable tools
   from operations you can implement with code in the sandbox. A missing dedicated
   tool need not prevent a small API client or parser. An existing service on the
   host does not grant the container credentials or administrative access.
3. Work in a unique draft directory under `/souz/workspace/skill-authoring/`.
   Execute the smallest real path from the input to the user's intended output.
   Scripts and local fixtures can support this investigation; do not substitute
   their success for the real integration. Read current interface documentation or
   inspect actual responses rather than guessing endpoints and parameters.
4. When an experiment fails, record the expectation, observed result, hypothesis,
   and next distinguishing check in a short working note outside the bundle.
   Change the experiment based on evidence. Check input, response and postcondition;
   a successful click or HTTP request does not prove the intended operation happened.
   Inspect full results using available Knowledge tools or bounded file reads.
   A truncated result does not establish that a required element is absent.
5. Continue through a materially different supported approach when appropriate.
   Do not repeat an unchanged failed action indefinitely. Do not turn one failed
   experiment into a blanket prohibition in the generated skill. Respect actual
   permissions and service restrictions; debugging is not authorization to bypass
   them. If progress needs an unavailable runtime capability or external access,
   report the specific missing capability and evidence rather than asking the user
   to perform the entire task manually.
6. Once the real path works, turn it into the smallest reusable skill. Put stable,
   deterministic operations in scripts where useful; keep decisions and tool
   coordination in instructions. Capture the method, inputs, outputs, dependency
   checks, success checks and recovery from observed failures. Keep investigation
   history in the workspace. Do not promise unimplemented capabilities in the
   description. Reuse private configuration by path, never by embedding its secrets.
7. Test a second ordinary input and relevant failure cases. Check actual content,
   not only existence or exit status. Verify external results when authorized
   (for example, download the produced file and compare it). A local fixture test,
   a component test and a complete user scenario prove different things: label them
   accordingly. Retain compact verification evidence outside the skill bundle.

If the full path is blocked, keep the unfinished work as a draft in the workspace
and state what works, what fails, and the smallest next action or runtime change.
Do not install a partial skill as a completed capability. The user can explicitly
choose to install a limited prototype; describe its actual scope accurately.

## Ownership and delegation

The main agent owns discovery, integration and final acceptance. Delegate bounded
work only when it helps; include the acceptance condition, known evidence, relevant
paths, authorization and exact available tool/skill IDs. Require the child to report
verified results, changed files and unresolved blockers. The parent's acceptance
check must evaluate the whole user task, not just repeat the child's final claim.

Souz children start with fresh history and only their selected tools and bundles.
They cannot discover additional skills or load a newly created skill by ID; do not
pass core discovery tools as skill IDs. A child with this skill can inspect/write
files and test a draft through inline commands, but the main agent must perform
`GetSkillByName` and invoke the new skill after installation. If a child lacks a
needed capability, have it return its evidence and pending step to the main agent.
Do not try to repair that limitation by writing unsupported tool calls into a skill.
Avoid simultaneous browser work: the user's browser is shared across conversations.

## Install and verify reuse

1. Choose a descriptive lowercase hyphenated ID. Check `GetSkillByName` and the
   destination for collisions. Do not shadow system, resource-backed or image skills.
2. Check the bundle rules below, then rename the verified draft into the absent
   `/souz/state/skills/<skill-id>/` destination on the same mount. An existing
   destination requires the edit workflow; never replace it to install a new skill.
3. Load the new ID with `GetSkillByName`. This checks bundle format and makes the
   current instructions available; it does not certify that the user task works.
   If loading fails, repair through this authoring skill before continuing.
4. Invoke the installed skill on the real acceptance input. Check stdout, stderr,
   exitCode, timedOut and the user's actual output. For instruction-only skills,
   execute their procedure. Installation is not the completion condition.
5. Exercise reuse without relying on the author's conversation: where delegation is
   available, a fresh child can receive the installed skill, necessary compiled
   tools, an ordinary user request and existing configuration paths. Do not supply
   the solution or the author's working notes. The child cannot reload discoveries;
   the parent should select its required capabilities. If a fresh-context check is
   unavailable, report it as unverified, not as passed. Fix missing instructions or
   dependencies revealed by this check and repeat the affected acceptance checks.
6. Report what can be used, how to invoke it, which complete scenarios passed and
   any remaining limitations. Clean up only your own temporary test side effects.

An exact lookup can load a newly created skill during this conversation. Automatic
inventory and description selection refresh on subsequent user turns.

## Edit

Inspect the existing `SKILL.md`, scripts, and callers before changing them. Only
edit the requested personal skill. If its directory contains `stored-skill.json`
or `bundles/`, stop this workflow and report that it needs an operator migration;
do not edit hash-addressed bundles or remove registry metadata. System skills and
the copies seeded from `/opt/souz/skills` are outside this workflow.

Keep the same ID and preserve unrelated files and user state. Save any temporary
backup outside the skill folder. Prepare replacement text before writing, and use
a sibling temporary file plus `os.replace` for each edit; remove temporary files
before loading the skill. This is atomic for one file, not for a multi-file change.
Do not edit while another known conversation or subagent is using that skill.

Re-read it with `GetSkillByName` and repeat the acceptance example through
`RunSkillCommand`. Instructions already loaded by an agent do not update by
themselves. A broken target can still be repaired through `skill-authoring`.
There is no version history or automatic rollback for these personal folders.

## Bundle format

`SKILL.md` starts with YAML frontmatter containing `name` and `description`:

```markdown
---
name: my-text-normalizer
description: Normalize whitespace in text when the user asks to clean up spacing.
---
Use RunSkillCommand with skillId my-text-normalizer, runtime PYTHON,
scriptPath scripts/normalize.py, and the input text in stdin.
Return the normalized text from stdout.
```

Use the description to explain when this skill is useful. In the body document
inputs, outputs, commands, dependencies, state paths, and failure handling.
Supporting scripts go in `scripts/`; reference text can go in `references/`.
The body should say how to read references through a command when needed.

All bundle files must be regular UTF-8 text, without symlinks or binary content.
Limits: 64 files, 128 KiB per file, 512 KiB total. Allowed extensions are cjs, css,
csv, html, js, json, jsx, md, mjs, py, sh, sql, text, toml, ts, tsx, txt, xml, yaml,
yml, and zsh. A `.ts` file being accepted does not provide a TypeScript runtime.

Keep generated outputs, logs, credentials, databases, dependency directories,
backups, and bytecode outside the bundle. Use `/souz/workspace/<skill-id>/` for work
and `/souz/state/<skill-id>/` for persistent state. Do not put `.git`, `venv`,
`node_modules`, or `__pycache__` in a skill. Python syntax checks can use
`compile(source, filename, "exec")` without writing bytecode.

## Verification and external actions

Use local fixtures and assertions to diagnose components, then verify the real
acceptance path. Test empty or invalid input as relevant.
Do not send messages, alter external accounts, make purchases, or change real data
just to test a skill unless the user has authorized those actions. Browser flows
share the user's live browser; tests can disturb their other conversations.

Never embed credentials in skill files or print them in diagnostics. Reuse the
available OAuth tools for supported providers. Report missing integrations or
dependencies explicitly; installing a skill does not enable additional tools,
grant credentials, or bypass the user's permissions.

For a filesystem creation/run/edit example (not an integration acceptance test), read `references/quickstart.md`
through `RunSkillCommand` in this skill. Adapt its names and logic to the request.
