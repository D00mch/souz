# Composite Skill commands

Declare a fixed sequence in `SKILL.md` frontmatter to run tools, bundled scripts, and waits through one `RunSkillCommand` call.

```yaml
---
name: Device observation
description: Read a device and format its state.
commands:
  observe:
    inputs: [device]
    steps:
      - id: settle
        waitMs: 500
      - id: state
        tool: device.get_state
        arguments:
          device: "${inputs.device}"
      - id: formatted
        script: scripts/format.py
        runtime: PYTHON
        args: ["${state}"]
    returns: "${formatted}"
---
Call observe with the device ID to get its formatted state.
```

The example requires an enabled `device.get_state` tool and a bundled `scripts/format.py`. Discovery returns each command's name and input names alongside the ordinary file-backed execution schema.

```json
{"skillId":"device-observation","arguments":{"composite":"observe","inputs":{"device":"tv-1"}}}
```

## Command behavior

- Steps run in order. Each has a unique ID and sets exactly one of `tool`, `script`, or nonnegative `waitMs`. Use literal tool names and bundle-relative script paths with `BASH`, `PYTHON`, or `NODE`. Scripts need no executable bit. The sequence has no branching, loops, nested commands, or subagent spawning. [Manifest validation](../agent/src/main/kotlin/ru/souz/agent/skills/bundle/CompositeCommandSpec.kt)
- The command uses the caller's filtered tool catalog and excludes core discovery, command, and subagent lifecycle tools. For child calls, include the Skill and every required tool in `SpawnSubagent.skillIds`. The manifest supplies script settings, and ordinary process options are ignored. [Sandbox and capability rules](../sharedLogic/docs/runtime-sandbox-and-skills.md) · [Invocation arguments and standalone `PROCESS` mode](../sharedLogic/src/main/kotlin/ru/souz/tool/skills/SkillCommandExecutor.kt)
- Inputs are required strings. Valid JSON is decoded, so `"true"` supplies a boolean. Wrap it in another layer of JSON quotes to pass it as text. References such as `${inputs.device}` and `${state.content[0].uri}` address declared inputs or earlier steps. Whole references keep their JSON type. Interpolated values and script arguments become text. Null and empty-string object fields are omitted. Tool content is decoded as JSON when possible, script stdout stays text, and `returns` becomes the command's `stdout`. [Reference grammar and rendering](../agent/src/main/kotlin/ru/souz/agent/skills/bundle/CompositeCommandSpec.kt)
- Missing inputs and unavailable tools fail before any step runs. Thrown errors, failed scripts, and unresolved references stop the sequence with a location in `stderr`. Error text returned by a tool is ordinary result data. The deadline covers the whole command and defaults to 60 seconds with a five-minute cap. Cancellation propagates, and completed effects persist without retries. [Execution and result handling](../sharedLogic/src/main/kotlin/ru/souz/tool/skills/CompositeCommandExecutor.kt)
- Later steps can use file outputs such as `${prepared.path}` or `${saved.fileId}`. Completed steps' `files` survive failure. Reply selections combine additions and removals in step order and apply only on success, subject to the reply limits. Legacy images come from the last tool step on success. [Attachment contracts](../backend/docs/attachments.md#tools-and-selection)
