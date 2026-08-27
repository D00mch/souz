---
name: device-mcp-call-tool
description: Perform an action or read a setting on a device (its own OS/app function — e.g. set the volume, read a network setting), reachable via the user's active client device. Use for any request to control or query a specific device once you know (or have looked up with device.mcp.list_tools) which function to use and its arguments.
metadata:
  souz.skill-id: device.mcp.call_tool
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Call an MCP tool on a device

Invoke `RunSkillCommand` with `skillId` set to `device.mcp.call_tool`. Arguments:
- `target`: optional device `id` from `device.mcp.list_devices`. Omit it to mean "the device
  currently on the call".
- `name`: required, the exact MCP tool name (from `device.mcp.list_tools`).
- `arguments`: object matching that tool's `inputSchema` exactly.

```json
{"skillId":"device.mcp.call_tool","arguments":{"target":"a1b2c3","name":"set_volume","arguments":{"level":7}}}
```

Souz sends a durable `tool.call.started` event named `device.mcp.call_tool` to the active client
device and waits up to one minute for `tool.result`. Don't pass a timeout — there is none to give;
the emulator applies its own internal budget for this call.

## Two kinds of failure — don't collapse them

The result preserves MCP's own two-level error model exactly:

- **The tool ran but the operation itself failed** (e.g. requested volume out of range, file not
  found) → `RunSkillCommand` succeeds and returns the raw MCP `CallToolResult`, with `isError: true`:
  ```json
  {"content":[{"type":"text","text":"level must be between 0 and 10"}],"isError":true}
  ```
  Treat this exactly like a normal tool response: read `content[]` (each item's `type` is `text`,
  `image`, or `resource`; `image` items carry `data`/`mimeType` directly, `resource` items carry a
  nested `resource` object with `uri` and either `text` or `blob`) and `isError`, and tell the user
  what actually happened — don't report this as a Skill/tool-call failure.
- **The call itself never reached the operation** (target unreachable, tool name doesn't exist,
  arguments don't satisfy `inputSchema`, timeout) → `RunSkillCommand` reports a `ClientError`:
  `device_not_connected`, `device_disconnected`, `device_timeout`, `mcp_target_not_found`,
  `mcp_server_unreachable`, `mcp_tool_not_found`, `mcp_invalid_arguments`. If the Skill reports
  missing client context, no active public WebSocket device is available for this execution.

Only claim the operation succeeded when you see `isError: false` (or absent) in the result — a
`ClientError` here means the operation's actual outcome is unknown, not that it failed to run.
