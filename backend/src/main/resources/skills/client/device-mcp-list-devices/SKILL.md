---
name: device-mcp-list-devices
description: List the smart devices reachable on the local network of the selected channel’s device, including the device itself. Call before device.mcp.list_tools or device.mcp.call_tool when targeting a device other than the selected channel’s device.
metadata:
  souz.skill-id: device.mcp.list_devices
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# List devices reachable on the network

Invoke `RunSkillCommand` with `skillId` set to `device.mcp.list_devices`. Takes no device arguments:

```json
{"skillId":"device.mcp.list_devices","arguments":{"channelId":"<provider-chat-uuid>"}}
```

Omit routing `channelId` to use the current channel. For an explicitly selected other device,
pass its provider’s `channelId` in `arguments`.

Result:

```json
{"devices":[{"id":"device-4471","name":"Кухонная станция","self":true},{"id":"a1b2c3","name":"home-server.local","self":false}]}
```

- `id` is stable across calls, conversations, and days — safe to reuse once seen.
- `self: true` marks the selected channel’s device; other entries are neighbors on its local network.
- No address or reachability field is included — use `id` only. A stale `id` surfaces later as
  `mcp_target_not_found`/`mcp_server_unreachable` from `device.mcp.list_tools`/`device.mcp.call_tool`.

To address the selected channel’s device, skip this Skill — just omit `target` in
`device.mcp.list_tools`/`device.mcp.call_tool`.

If the Skill reports missing client context, the selected channel is unavailable.
Other failures surface as `ClientError` codes: `device_not_connected`,
`device_disconnected`, `device_timeout`, `mcp_discovery_failed`.
