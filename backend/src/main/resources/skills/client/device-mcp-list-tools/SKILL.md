---
name: device-mcp-list-tools
description: List what a device can actually do — its available functions (e.g. volume control, network info) — reachable via the user's active client device. Call before device.mcp.call_tool for the exact tool name and input schema; don't guess either.
metadata:
  souz.skill-id: device.mcp.list_tools
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# List what a device can do

Invoke `RunSkillCommand` with `skillId` set to `device.mcp.list_tools`. Arguments:
- `target`: optional device `id` from `device.mcp.list_devices`. Omit it to mean "the device
  currently on the call" — exactly like omitting `target` on `device.mcp.call_tool`.

```json
{"skillId":"device.mcp.list_tools","arguments":{"target":"a1b2c3"}}
```

Souz sends a durable `tool.call.started` event named `device.mcp.list_tools` to the active client
device and waits up to 30 seconds for `tool.result`. The result is the target's raw MCP
`ListToolsResult`, unchanged:

```json
{"tools":[{"name":"set_volume","description":"Set the device's output volume","inputSchema":{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"]}}]}
```

`inputSchema` is the tool's real MCP JSON Schema — build `device.mcp.call_tool`'s `arguments` to
satisfy it exactly, don't guess a shape.

If the Skill reports missing client context, no active public WebSocket device is available for this
execution. Other failures surface as `ClientError` codes: `device_not_connected`,
`device_disconnected`, `device_timeout`, `mcp_target_not_found` (the `target` id is unknown or has
gone stale since discovery), `mcp_server_unreachable`, `mcp_protocol_error`.
