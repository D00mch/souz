# Device discovery: client handoff contract

**Status: proposed v1; not implemented or available for production use.** This package is an agreement for the Souz and Orion/Assistant teams to implement later. The [implemented public API](../../public-souz-contract/README.md) remains the reference for existing clients. All requirements below describe the proposed extension, including its connection role and durable delivery.

Souz uses `device.list` to obtain the requesting user's registered devices before choosing a destination, including when a task starts in Telegram. No selected device, device chat, or chat subscription is needed. Tool catalogue discovery and device commands belong to separate contracts; see [issue #807](https://github.com/D00mch/souz/issues/807).

The handoff consists of this specification, [OpenAPI frame schemas](openapi.yaml), and [complete example exchanges](examples.jsonl). Send only an example's `body` over the socket; `scenario`, `direction`, and `note` describe the trace. Schemas reuse the public API's envelope types through relative references; distribute both directories together. They do not extend the implemented `ClientMessage` or `SouzFrame` unions.

## 1. Identify and route the Assistant connection

Assistant opens a dedicated service socket on each Souz process that owns requesting tasks:

```text
wss://<owner-address>/v1/ws?clientType=backend&service=assistant
Sec-WebSocket-Protocol: souz.assistant.v1
```

`service=assistant` selects the inventory service role during the upgrade. It is not a credential. The deployment must restrict this role to trusted Assistant instances at ingress, using service identity or a dedicated restricted listener. Ordinary backend clients must not be able to claim it. The client team receives owner-specific connection addresses and access configuration from the deployment; an unpinned load-balanced address is insufficient.

The server must select `souz.assistant.v1` in the upgrade response. Assistant closes a connection that does not negotiate that subprotocol without sending results. A legacy server accepting the URL while ignoring `service` is not a registered Assistant connection.

| Upgrade outcome | HTTP status before WebSocket upgrade |
| --- | --- |
| Authorized Assistant; owner's service slot is free | `101` with the selected subprotocol; the socket is registered and ready for calls, without a registration frame. |
| Invalid `clientType`/`service` combination, duplicate query parameter, or missing/unsupported subprotocol | `400` |
| Caller is not authorized for the Assistant role | `403` |
| Another Assistant socket holds this owner's slot | `409`; leave the existing socket active; retry with backoff. |
| Extension disabled or owner not accepting service connections | `503`; retry with backoff. |

There is exactly one active Assistant socket per Souz runtime owner, shared by that owner's users. Multiple Assistant replicas compete for the same slot; acceptance is atomic. Disconnect or detection of a dead socket releases only that socket's registration. A stale socket's cleanup must not remove a replacement. WebSocket ping/pong detects a lost connection; a contender never silently replaces a live socket.

The originating runtime owner persists and dispatches the call only through its registered Assistant socket. Ordinary backend sockets and chat subscribers never receive these service requests, including through chat replay. This socket carries only `device.list` requests, their `tool.result` responses and ACKs; it has no chat subscriptions or chat commands. Results are accepted only on the owner's current Assistant connection and only for calls assigned to that service. A result on an ordinary socket, or for another owner's/service's call, receives `tool_call_not_found` without changing the call.

If no Assistant is connected, the call stays pending until a service connects or the original deadline expires. Never choose an arbitrary backend socket or interpret service absence as an empty inventory. Reconnect goes to the same owner. This proposal does not promise migration or resumption of a live task after its Souz process dies; owner recovery must settle abandoned calls before serving them again. Supporting crash recovery for Telegram tasks is separate implementation work, not an implied property of durable receipts.

## 2. Request and user identity

Souz sends `tool.call.started` with the existing event envelope and the following payload:

```json
{
  "kind": "event",
  "seq": 7,
  "type": "tool.call.started",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "payload": {
    "toolCallId": "inventory-001",
    "name": "device.list",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "arguments": {},
    "deadlineAt": "2026-09-30T12:00:30Z"
  },
  "createdAt": "2026-09-30T12:00:00Z"
}
```

- `userId` is the originating task's trusted Souz owner, supplied by Souz, never by the model. Assistant resolves that identity to its account registry and returns only that account's devices. An unresolved identity is a lookup failure, not an empty inventory. Tokens and identity are forbidden in `arguments`.
- `chatId` and `threadId` identify the existing originating task, including a Telegram task; neither identifies a destination device chat. Assistant echoes them and `toolCallId` unchanged. It does not create chats or threads for discovery.
- `arguments` is exactly `{}`. Device targeting fields are absent. A new request has a new `toolCallId`.
- `seq` is a positive durable sequence within the originating chat. Service delivery is a filtered stream with possible gaps, not a subscription cursor. Deduplicate by `(chatId, threadId, toolCallId)`; do not send `chat.subscribe` or echo `seq`/`createdAt` in results.
- `deadlineAt` is the absolute UTC result deadline; the default budget is 30 seconds from `createdAt`. Delivery and retries do not extend it. Assistant must not start a lookup at or after the deadline. Souz's clock decides result admission; deployments must synchronize clocks.

## 3. Inventory and failures

Assistant returns `tool.result` with `status:"succeeded"` and `result:{"devices":[...]}`, omitting `error`. The array is the complete registered inventory, including offline devices; a resolved account with no registered devices returns `[]`. Do not return a partial inventory as a success.

| Device field | Requirement |
| --- | --- |
| `deviceId` | Nonblank stable ID within the user's account, matching `message.submit.payload.device.deviceId` for that device. Unique in the response; two different entries cannot share an ID. |
| `name` | Nonblank user-facing name. Names may repeat and are not identifiers. |
| `deviceType` | `tv_box`, `smart_speaker`, `smartphone`, or `unknown`, matching the public device vocabulary. Required; use `unknown` when the registry cannot classify the device. |
| `online` | Boolean reachability at lookup time. It does not guarantee a later command succeeds. |
| `capabilities` | Distinct tags from `speech`, `screen`, `device_tools`, `user_permissions`, `deep_links`, `oauth`. May be empty; these are metadata, not tool definitions or permission grants. |

This is an account inventory, not [local MCP host discovery](../../device-mcp-call/mcp-tool-call-contract.md). A local `{id,name,self}` list or a list of chats cannot substitute for it. Device selection and later ownership checks remain separate operations.

A non-success result contains `error` and omits `result`. Optional `error.details` is a JSON object; do not include credentials or raw upstream responses.

| Assistant outcome | `status` | `error.code` |
| --- | --- | --- |
| Registry unavailable, incomplete lookup, or user mapping unavailable | `failed` | `internal_error` |
| Assistant exhausts its lookup budget before Souz's deadline | `timed_out` | `client_tool_timed_out` |

The timeout result must reach Souz before `deadlineAt` to be accepted as the first client result. Otherwise Souz settles the timeout itself. A failed lookup must never masquerade as `devices:[]`.

## 4. Result acceptance and retries

Souz validates correlation, service ownership, the operation-specific result schema, and unique `deviceId` values before accepting a result. Success and failure both receive the existing tool-result ACK shape; `status:"accepted"` means the terminal result was recorded, not that lookup succeeded.

| Call state / received result | ACK and effect |
| --- | --- |
| Pending; valid result received strictly before `deadlineAt` | `accepted`, `duplicate:false`; atomically record the result and receipt. |
| Same accepted client result retried, even after its deadline or thread completion | `accepted`, `duplicate:true`; return the stored receipt with only `duplicate` changed. |
| Different result after a client result was accepted | `rejected`, `duplicate:false`, `idempotency_conflict`; keep the first result. |
| No accepted client result; deadline reached or source task cancelled | `rejected`, `duplicate:false`, `tool_call_not_found`; do not revive the wait or overwrite its terminal state. |
| Unknown call, mismatched correlation, owner or service | `rejected`, `duplicate:false`, `tool_call_not_found`; do not change any call. |
| Identifiable pending call, invalid result shape or repeated device IDs | `rejected`, `duplicate:false`, `invalid_request`; the call remains pending until a valid result, cancellation or deadline. |

Result admission, timeout and cancellation are mutually exclusive. An on-time admitted result remains accepted even if its ACK write finishes after the deadline. Souz persists the terminal result and receipt before attempting the ACK, and releases the task after that write succeeds or fails; a broken socket cannot leave it waiting indefinitely. Lost ACKs do not undo acceptance. Malformed JSON or frames without usable correlation close the service socket with code `1008`; no ambiguous ACK is sent.

The idempotency key is `(chatId, threadId, toolCallId)`. Equality compares terminal status and JSON payload, ignoring object property order but preserving array order. Assistant stores one immutable terminal result and resends it after a lost ACK, including after `deadlineAt` to reconcile acceptance. It must not query the registry again or reorder devices when retrying that result. `invalid_request` permits correcting a rejected result before the deadline because no receipt was accepted. Other rejection ACKs are terminal for that attempt.

Assistant retains its work/result record until both the deadline has passed and a terminal ACK has been received. Replicas taking over a service connection must share or recover these records. Souz retains accepted receipts for the stored call's lifetime; deletion yields `tool_call_not_found` and never recreates a call. Rejected ACKs use a new `receivedAt`; accepted duplicate ACKs preserve the original receipt timestamp.

## 5. Reconnect and cancellation

Souz persists the request before its first delivery. A service disconnect neither cancels the source task nor pauses its deadline. After a successful reconnect to the same owner, Souz automatically sends only unexpired pending calls for its live tasks, with every original request field unchanged. No client cursor or device subscription is needed. Live arrivals and pending redelivery must not leave a delivery gap; duplicates are permitted and ordering between different calls is unspecified.

Assistant joins work already running for a repeated key, or resends its saved terminal result. It also retries unacknowledged results on reconnect without waiting for request redelivery: accepted calls are no longer pending and therefore will not be redelivered. After the deadline, an uncached replay never starts a new lookup. A result retry can still recover its ACK.

If Souz records a timeout or the originating task is cancelled first, the call leaves the pending set. There is no separate cancellation frame in this proposal; Assistant's read-only lookup stops by its deadline, and any late result follows the rejection table. The originating task cannot be resumed by that result. A client result already accepted remains available for idempotent acknowledgement even if the task is subsequently cancelled.

## Implementation acceptance scenarios

- Authorize the Assistant upgrade; reject competing sockets and invalid roles; verify ordinary sockets cannot receive or resolve service calls. In a two-owner deployment, route each owner's tasks only to its own Assistant connection.
- Discover from Telegram with no device chat or subscription, using trusted user identity. Distinguish a mixed online/offline inventory, a genuinely empty registry and a lookup failure; prevent data from one user entering another's result.
- Validate schemas and semantic rules: reject malformed envelopes, both result and error, missing correlation, wrong enum values and repeated device IDs.
- Disconnect before delivery and during lookup; reconnect to the same owner and deduplicate redelivery. Disconnect after persistence but before ACK receipt; resend the stored result and recover the duplicate ACK without a new lookup.
- Race results against deadlines and cancellation; accept one outcome, preserve on-time admission through a slow/failed ACK write, and reject late success. A second Assistant replica must recover the first replica's cached result.
- Keep existing public schemas, normal chat subscriptions and live-only cross-channel calls unchanged. Runtime recovery and later device command execution require their own implementation and verification.
