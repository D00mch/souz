# Bruno

Import [Client-Souz Contract.yml](Client-Souz%20Contract.yml) as an OpenCollection and select its bundled `local` environment. [local.json](local.json) contains the same defaults for separate environment import. Use a writable folder outside this repository for the imported collection.

Set `baseUrl` and `wsBaseUrl` separately in the selected environment: an `https://` service uses `wss://` for WebSocket, preserving the host, port and any path prefix. The bundled environment uses local HTTP and `ws://`. Re-import the collection to update saved request templates, preserving your environment values.

Start the backend and PostgreSQL from the repository root with `docker compose up --build`, then check Health with `curl --fail http://127.0.0.1:8080/health`. Compose enables `SOUZ_FEATURE_WS_EVENTS=true`. Chat creation and `history.append` do not invoke a model. Before `message.submit`, configure provider credentials and a model on the backend for both `userId` and `userIdB`; see [local backend model and key setup](../../../README.md#local-backend-model-and-key-setup). Keep credentials out of the collection.

## Two chats on one socket

1. Open `websocket / Client-Souz multi-chat WebSocket`, set `clientType=backend` and connect to `/v1/ws?clientType=backend`. Send `01 chat.create A` and `02 chat.create B`.
2. Copy each creation ACK's `chatId` into `chatId` (A) or `chatIdB` (B) in the selected environment. The ACK's `userId` identifies the owner. Repeating creation reuses the same chat; change `createChatRequestId` only when you want new chats. The shared request ID is scoped per user.
3. Send `03 message.submit A`, then `04 message.submit B` while A is waiting for a tool reply. Copy each accepted submit ACK's `thread.id` into `threadId` or `threadIdB`. Route ACKs, status, and events by `chatId`; the two chats may interleave.
4. For A's `tool.call.started`, copy the event's `threadId` and `payload.toolCallId` into `threadId` and the matching `toolCallIdAsk` or `toolCallIdOpenMedia`. Every public `tool.call.started` requests client execution; its payload has no `target` field. Send template `05` or `06` for the actual pending call before its `deadlineAt`. Adjust the result to the call's arguments; model-selected calls and their order can vary.
5. Read the final response in `thread.completed.payload.response`. Repeat either submit template to send new input.

Every `message.submit` and `history.append` uses `{{$randomUUID}}`, so each send has a fresh request ID. Ordinary multi-chat submissions omit `threadId`: Souz selects the active thread or creates one when none exists. For a deliberate retry, copy the original resolved UUID and unchanged payload from the sent message; sending the template again is a new operation.

## History

After creating chat A and setting `chatId`, send `09 history.append A text` or `10 history.append A tool_call` on the multi-chat socket. Both expect an accepted ACK and store context for the next `message.submit`; they do not start execution or resolve a pending tool call.

Tool history uses `role: "assistant"` and `content.type: "tool_call"`, with `name`, `arguments` and `result`. Both `arguments` and `result` are JSON objects; history content has no `toolCallId` or `target`. Use `tool.result` templates `05` or `06` to answer a pending call.

## Reconnect

Save each chat's last successfully processed event `seq` in `afterSeq` or `afterSeqB`. These values must be nonnegative integers; the templates insert them without JSON quotes. `0` requests all retained public events.

Disconnect, reconnect the same multi-chat request, and send `07 chat.subscribe A reconnect` and `08 chat.subscribe B reconnect`, without submitting input. Each explicit cursor replaces only that chat's stream, acknowledges with `duplicate:false`, replays `seq > afterSeq`, then delivers live events. Track progress independently and deduplicate by `(chatId, seq)`.

Automatic subscriptions from creation or accepted submits are live-only, including events caused by the submit. They do not recover earlier events. A subscription with an omitted cursor keeps an existing stream with `duplicate:true`; on an unsubscribed chat it replays from `0`. Disconnect does not cancel execution or extend pending tool deadlines.

## HTTP and compatibility

`http / Create chat` creates or retrieves chat A and sets `chatId` for the current session without persisting it. It shares creation idempotency with `01 chat.create A`; the existing HTTP retry checks the same result. `Get thread status` uses `chatId` and `threadId`; substitute the B variables in its URL to inspect B.

`websocket / Client-Souz single-chat WebSocket (existing chat)` connects to `/v1/chats/{{chatId}}/ws` and replays from `0`. Set an existing `chatId` first; `chat.create` and `chat.subscribe` belong to the multi-chat endpoint. Its explicit continuation template requires the current `threadId` and `acceptsInput:true`; it also uses a fresh message UUID.

See the [public contract](../README.md), [schemas](../openapi.yaml), and [example trace](../examples/happy-path.jsonl) for other operations.
