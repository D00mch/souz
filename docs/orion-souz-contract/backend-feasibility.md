# Backend Feasibility

This document maps the Orion contract to the current Souz backend.

## Fit Summary

The text-to-agent-to-final-response path is feasible on the current backend architecture. Souz already has trusted proxy identity, user-scoped chats, PostgreSQL-backed messages, agent conversation state, execution lifecycle, one-active-execution protection, cancellation, durable event replay, and live WebSocket events.

The full Orion loop needs targeted additions around session/device metadata, strict idempotency, inbound WebSocket commands, and device-tool adapters.

## Capability Map

| Area | Current backend support | Contract status |
| --- | --- | --- |
| Trusted user identity | `/v1/**` requires `X-Souz-Proxy-Auth` and `X-User-Id`; services scope every user-owned operation by trusted user ID. | Supported if `X-User-Id` is the validated `SberID.UUID`. |
| Raw audio and SberID token | Backend accepts text messages; it does not process Orion audio or SberID tokens. | Orion/proxy boundary must validate token and run ASR before Souz. |
| Chat lifecycle | `POST /v1/chats` creates a user-owned chat; there is no Orion `sessionId -> chatId` resume mapping. | Add session metadata and idempotent open/resume behavior. |
| Message submission | `POST /v1/chats/{chatId}/messages` persists a user message and starts an execution. | Supported. Contract uses async `202`; current route returns `200` with execution state. |
| Request IDs | `clientMessageId` is stored and used for dedupe; `request_id` exists on `agent_executions` but is not populated by the message route. | Wire Orion `requestId` into execution state or metadata. |
| Device metadata | Message metadata currently stores `clientMessageId`; execution metadata stores model/runtime settings. | Store `deviceId`, `deviceType`, `sessionId`, and channel metadata. |
| Idempotency | Repeat `clientMessageId` returns the latest matching execution in the chat; payload conflicts are not checked and no DB unique index enforces it. | Add payload hash and unique constraint for strict contract guarantees. |
| Execution conflicts | PostgreSQL enforces one active execution per user/chat. | Supported. Orion must handle `409 chat_already_has_active_execution`. |
| Event replay | `GET /v1/chats/{chatId}/events?afterSeq=` replays durable events; WebSocket sends replay then live events. | Supported. Expose `eventId` if Orion needs event-level dedupe. |
| WebSocket commands | Current `/ws` route is Souz -> client only. It does not receive Orion commands or send command acknowledgements. | Add inbound command loop, command idempotency store, and acks. |
| User permissions | Backend has `option.requested` durable events and `POST /v1/options/{optionId}/answer` for continuation. | Supported as REST fallback; add WS `option.answer` command for Orion symmetry. |
| Device tools | Backend persists generic tool-call lifecycle events but has no Orion device-tool host adapter. | Add backend-safe Orion tool adapter that emits `device.tool.requested` and waits for result command. |
| Local Python/JS/Node tools | Backend-safe runtime tools and sandbox scope exist. | Supported for tools that do not need desktop UI or device capabilities. |
| External HTTP tools | Backend-safe web tools exist. | Supported where credentials and policy allow direct Souz access. |
| OAuth/deep links/smartphone channel | No general Orion/mobile callback channel is present in backend. | Define channel ownership per skill before implementation. |
| Memory/facts | Backend persists product messages and agent continuation state in PostgreSQL. The agent memory runtime is not wired to a PostgreSQL fact store in backend. | Session/global facts require backend memory storage integration. |
| S3 archival | PostgreSQL is the structured repository store. | S3 archival is outside this contract. |

## Minimal Backend Work For Orion MVP

1. Add Orion session metadata to chat opening and message submission.
2. Populate execution `request_id` from Orion `requestId`.
3. Store `deviceId`, `deviceType`, `sessionId`, and channel metadata with message/execution records.
4. Return async message acceptance consistently, or document the current `200` response for MVP.
5. Expose `eventId` on event DTOs if Orion uses event-level dedupe.

## Work For Device Tools And Permissions

1. Add a command ingress path on `/v1/chats/{chatId}/ws` or a REST callback endpoint.
2. Persist command acknowledgements by `commandId`.
3. Add an Orion device-tool adapter that can suspend an agent tool call until Orion returns `device.tool.result`.
4. Map `option.requested` to Orion user permission UI and accept `option.answer` over WebSocket.

## Current Feasibility Decision

The contract is suitable as a shareable target. The text-only assistant path can be implemented with small backend extensions. The full loop with device tools and Orion-mediated permissions is feasible but requires new inbound command handling and an Orion tool adapter.
