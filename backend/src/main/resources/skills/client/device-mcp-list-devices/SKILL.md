---
name: device-mcp-list-devices
description: List the smart devices reachable on the local network of the user's active client device, including the device itself. Call before device.mcp.list_tools or device.mcp.call_tool when targeting a device other than the one currently on the call.
metadata:
  souz.skill-id: device.mcp.list_devices
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# List devices reachable on the network

Invoke `RunSkillCommand` with `skillId` set to `device.mcp.list_devices`. Takes no arguments:

```json
{"skillId":"device.mcp.list_devices","arguments":{}}
```

Result:

```json
{"devices":[{"id":"device-4471","name":"Кухонная станция","self":true},{"id":"a1b2c3","name":"home-server.local","self":false}]}
```

- `id` is stable across calls, conversations, and days — safe to reuse once seen.
- `self: true` marks the device currently on the call; other entries are neighbors on its local network.
- No address or reachability field is included — use `id` only. A stale `id` surfaces later as
  `mcp_target_not_found`/`mcp_server_unreachable` from `device.mcp.list_tools`/`device.mcp.call_tool`.

To address the device currently on the call, skip this Skill — just omit `target` in
`device.mcp.list_tools`/`device.mcp.call_tool`.

If the Skill reports missing client context, no active public WebSocket device is available for this
execution. Other failures surface as `ClientError` codes: `device_not_connected`,
`device_disconnected`, `device_timeout`, `mcp_discovery_failed`.
