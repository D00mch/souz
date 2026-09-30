# Device discovery for Orion/Assistant

**Status: proposed; requires implementation in Souz and Orion/Assistant.** This is the device inventory companion to [issue #807: Client tool discovery and calls](https://github.com/D00mch/souz/issues/807). Device command targeting and dynamic tool catalogue discovery are separate contracts.

## Connection and ownership

The Assistant service connects to `/v1/ws?clientType=backend` using one shared connection for multiple users on the backend process owning the originating task. Souz can request an inventory during an existing task, including a Telegram task, without a selected device, device chat, or device chat subscription.

Souz supplies `payload.userId` from the originating task's trusted owner; the model does not choose it. The Assistant resolves that same user in its registry and returns only devices registered to that user. No tokens or user identity appear in `arguments`.

The originating `chatId` and `threadId` correlate the exchange with the task that needs the inventory. They do not identify a destination device chat. The Assistant echoes them and `toolCallId` unchanged; it does not create a replacement chat or thread. `seq` and `createdAt` are Souz event metadata and are not echoed in results.

This delivery path extends the [implemented public contract](README.md): current cross-channel calls require a subscribed target chat and are live-only. `device.list` instead reaches the shared Assistant connection directly and has durable semantics. The source task need not have started over WebSocket.

## Request: Souz → Assistant

Use the existing `tool.call.started` event envelope, with a positive integer `seq`, and this operation's payload. `arguments` is an empty object. `deviceId` and `product` are omitted because no device has been selected.

```json
{
  "kind": "event",
  "seq": 7,
  "type": "tool.call.started",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "payload": {
    "toolCallId": "call-device-list-001",
    "name": "device.list",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "arguments": {},
    "deadlineAt": "2026-09-30T12:00:30Z"
  },
  "createdAt": "2026-09-30T12:00:00Z"
}
```

Each new request gets a new `toolCallId`. The default deadline is 30 seconds; the Assistant respects the absolute `deadlineAt` and cannot extend it on replay.

## Result: Assistant → Souz

Use the existing `tool.result` envelope. Success requires `status:"succeeded"` and an object-valued `result`, without `error`:

```json
{
  "kind": "tool.result",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "toolCallId": "call-device-list-001",
  "status": "succeeded",
  "result": {
    "devices": [
      {
        "deviceId": "device-kitchen-001",
        "name": "Kitchen speaker",
        "product": "sberbox",
        "online": true,
        "capabilities": ["speech", "device_tools"]
      },
      {
        "deviceId": "device-bedroom-002",
        "name": "Bedroom speaker",
        "product": "sberbox",
        "online": false,
        "capabilities": ["speech", "device_tools"]
      }
    ]
  }
}
```

`devices` is the complete registered inventory for the requesting user, including offline devices. An empty registry returns `{"devices":[]}` successfully. Every entry requires:

| Field | Meaning |
| --- | --- |
| `deviceId` | Nonblank, stable identity within the user's account, matching `message.submit.payload.device.deviceId` for the same device. IDs must be unique within a response. |
| `name` | Nonblank user-facing device name. Names may repeat; use `deviceId` for identity. |
| `product` | Nonblank product identifier, such as `sberbox`. |
| `online` | Boolean availability at lookup time, not a guarantee that a later command will succeed. |
| `capabilities` | Array of distinct existing tags: `speech`, `screen`, `device_tools`, `user_permissions`, `deep_links`, `oauth`. An empty array is valid. Tags are metadata, not callable tool definitions or permission grants. |

This account inventory differs from `device.mcp.list_devices`, which discovers local MCP hosts through a selected client device and returns `{id,name,self}`. Neither local discovery results nor a chat list replace the registered account inventory.

## Errors and acknowledgement

A non-success result requires `error` and omits `result`. Use the existing error envelope and codes; optional `error.details` must be an object. Lookup failures must not be reported as a successful empty inventory.

| Situation | `status` | `error.code` |
| --- | --- | --- |
| Registry lookup fails | `failed` | `internal_error` |
| Result deadline expires | `timed_out` | `client_tool_timed_out` |

Souz acknowledges accepted results, including reported failures, before resuming the originating task:

```json
{
  "kind": "ack",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "toolCallId": "call-device-list-001",
  "status": "accepted",
  "duplicate": false,
  "error": null,
  "receivedAt": "2026-09-30T12:00:02Z"
}
```

`accepted` confirms result receipt, not operation success. If no result arrives before `deadlineAt`, Souz records `timed_out` / `client_tool_timed_out` itself; the client need not send a timeout result. Cancellation of the originating task cancels the pending discovery. A late result cannot resume a cancelled task or overwrite a recorded timeout.

## Persistence, reconnect, and retries

Souz persists the call in the originating thread and persists the terminal result before sending its ACK. Disconnect does not cancel the pending task. On reconnect, Souz redelivers unexpired pending inventory calls directly to the shared Assistant connection, without a device chat subscription. Redelivery preserves all original frame fields, including `seq`, `createdAt`, `toolCallId`, and `deadlineAt`; it does not create a new call. Resolved or expired calls are not pending-call redeliveries.

The Assistant deduplicates execution by `(chatId,threadId,toolCallId)` and retains a terminal result for retries. Repeated delivery joins work already in progress or resends the stored result; it must not replace that result with a fresh inventory snapshot. If an ACK is lost, resend the exact stored result. Souz returns `duplicate:true` for an identical terminal-result retry, and a rejection with `idempotency_conflict` for a different terminal result. These are the durable semantics used by issue #807, not the `seq:null` cross-channel semantics.

See [device-discovery.jsonl](examples/device-discovery.jsonl) for complete exchanges covering a mixed online/offline inventory, an empty inventory, lookup failure, timeout, and a conflicting late success. Each line has trace metadata; `body` is the complete wire frame. The trace starts with an existing originating task and connected WebSocket; creation, connection setup, and Telegram input are outside it.

## Schemas

[OpenAPI](openapi.yaml) contains standalone proposed schemas `DeviceListRequest`, `DeviceListPayload`, `DeviceListArguments`, `DeviceListResult`, `DeviceListDevice`, and `DeviceListToolResult`. They reuse the existing UUID, capability, result-envelope, and ACK schemas. The implemented `ToolCallStartedPayload` does not accept `userId`; these proposed schemas and delivery rules require backend implementation before clients can use them. There are no additional REST routes or frame kinds.
