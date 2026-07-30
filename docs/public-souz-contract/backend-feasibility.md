# Backend Feasibility

This document maps the Client contract to the current Souz backend.

## Fit Summary

Souz already has trusted proxy identity, user-scoped chats, PostgreSQL-backed messages, agent conversation state, execution lifecycle, cancellation, durable event replay, and live WebSocket events.

The public thread is an aggregate above the current backend execution. It needs inbound WebSocket messages, atomic message-and-thread acknowledgement, strict idempotency, and Client tool adapters.

## Capability Map

| Area | Current backend support | Contract status |
| --- | --- | --- |
| Client profiles | Backend clients use trusted proxy headers; mobile app clients use a separate mobile auth profile. | Store and validate `clientType = backend \| mobile_app` at chat creation and WebSocket connection. |
| Trusted user identity | Services scope every user-owned operation by the authoritative Souz user ID. Backend clients provide it with `X-User-Id`; mobile app clients resolve it from auth. | Supported after the mobile auth profile is wired at the identity boundary. |
| Raw audio and external tokens | Backend accepts text messages; it does not process Client audio or external auth tokens beyond identity resolution. | Client or the auth boundary validates tokens and runs ASR before Souz. |
| Chat lifecycle | `POST /v1/chats` creates a user-owned chat. | Persist create-chat idempotency by `(userId, requestId)`. |
| Message submission | `POST /v1/chats/{chatId}/messages` persists a user message and starts an execution. | Reuse the execution service after persisting the public message and thread association. |
| Thread lifecycle | Backend executions are scoped to one agent run. | Add a thread aggregate that can span several user submissions, tool calls, and internal executions. |
| Input context | Message metadata stores limited client data; execution metadata stores model/runtime settings. | Store content `source` and device capabilities from `message.submit`. |
| Message acknowledgement | Message dedupe exists, but payload conflicts and acknowledgements are not persisted. | Persist normalized payload hash, thread association, and acknowledgement by `(chatId, requestId)`. |
| Active work conflicts | PostgreSQL enforces one active execution per user/chat. | Accepted submissions to a running thread append to its input log and must be observed before the terminal event; internal execution interruption, supersession, or continuation is backend-owned. |
| Event replay | The chat WebSocket sends replay followed by live events. | Use the chat-local `seq` for ordering, replay, and deduplication. |
| WebSocket input | Current `/ws` route is Souz -> client only. | Add inbound handling for `message.submit`, `tool.result`, and `thread.cancel` on `/v1/chats/{chatId}/ws`. |
| Cancellation | Backend has execution cancellation services and an HTTP route. | Cancel the active internal execution and persist one terminal thread event. |
| User input tools | Backend has durable option events and an HTTP answer route. | Adapt user questions to `tool.call.started` with `target = client`; accept `tool.result` and return `ToolResultAck`. |
| Device tools | Backend persists generic tool-call lifecycle events but has no Client device-tool host adapter. | Emit `tool.call.started` with `target = client`; persist terminal result acknowledgements by `(chatId, threadId, toolCallId)`. |
| Local Python/JS/Node tools | Backend-safe runtime tools and sandbox scope exist. | Supported for tools that do not need desktop UI or device capabilities. |
| External HTTP tools | Backend-safe web tools exist. | Supported where credentials and policy allow direct Souz access. |
| OAuth/deep links/smartphone channel | No general Client/mobile callback channel is present in backend. | Define channel ownership per skill before implementation. |
| Memory/facts | Backend persists product messages and agent continuation state in PostgreSQL. The agent memory runtime is not wired to a PostgreSQL fact store in backend. | Chat/global facts require backend memory storage integration. |
| S3 archival | PostgreSQL is the structured repository store. | S3 archival is outside this contract. |

## Minimal Backend Work For Client MVP

1. Add inbound Client-message handling and acknowledgements for message submissions, tool results, and thread cancellation to `/v1/chats/{chatId}/ws`.
2. Add thread persistence with atomic input-log append, `inputSeq`, thread `revision`, and first-submission acknowledgement.
3. Persist create-chat normalized payload hashes by `(userId, requestId)`, message-submission and thread-cancellation normalized payload hashes and acknowledgements by `(chatId, requestId)`, and tool-result terminal payload hashes and acknowledgements by `(chatId, threadId, toolCallId)`.
4. Route accepted submissions through the existing message and execution service, including running-thread submissions that arrive while an internal execution is active.
5. Store `clientType`, content `source`, and device capabilities and expose chat-local event `seq`.

## Work For Device Tools And Permissions

1. Route `thread.cancel` to the existing cancellation service.
2. Add a Client tool adapter that can suspend an agent tool call until Client returns `tool.result`, then accept or reject the result with `ToolResultAck`.
3. Map user questions and permissions to Client-targeted tool calls.

## Current Feasibility Decision

The contract is suitable as a shareable target. The main persistence gap is the thread aggregate spanning several user submissions and internal executions. Client-mediated tools also require a backend-safe Client tool adapter.
