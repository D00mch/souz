# Composite Skill commands

Declare a fixed sequence in `SKILL.md` frontmatter to run tools, bundled scripts, and waits through one `RunSkillCommand` call:

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

Each step has a unique identifier and exactly one of `tool`, `script`, or nonnegative `waitMs`. Script steps require an explicit runtime (`BASH`, `PYTHON`, `NODE`, or `PROCESS`) and a path inside the bundle. Tool names and script paths are literal. Commands are linear: no branching, loops, nested composites, or subagent spawning.

Inputs are required string values. Valid JSON strings are decoded, so `"{\"x\":1}"` supplies an object and `"true"` supplies a boolean. Quote a JSON-looking value again to preserve it as text. References such as `${inputs.device}` and `${state.content[0].uri}` may target declared inputs or earlier steps. Input and step identifiers use letters, digits, and underscores, beginning with a letter or underscore; `inputs` is reserved as a step ID.

A value consisting entirely of a reference preserves its JSON type. Embedded references and script arguments use text, with objects and arrays encoded as JSON. Null and empty-string object fields are omitted. Tool message content is decoded as JSON when possible; script stdout always stays text, including whitespace. `returns` uses the same reference syntax and becomes the result's `stdout`. Attachments are not forwarded by composite steps.

Tool steps can access only the invoking `RunSkillCommand` catalog. For a child, select the file-backed Skill and all required tools explicitly in `SpawnSubagent.skillIds`. Core discovery, command, and spawn tools cannot be called from a composite. Ordinary script execution retains its existing sandbox behavior.

Unavailable tools and missing inputs fail before any steps run. A thrown tool error, failed script, or unresolved reference stops the sequence; tool content describing an error remains ordinary result data. Completed side effects are not rolled back or retried. The usual command result (`exitCode`, `stdout`, `stderr`, `timedOut`) identifies failures by step. Cancellation propagates. `timeoutMillis` bounds the entire sequence, defaults to 60 seconds, and is capped at five minutes. Other process arguments are ignored when `composite` is set; script steps take their runtime, path, and arguments from the manifest.
