# Postman

Import `local.postman_environment.json` and `client-souz-http.postman_collection.json` into Postman.

Run `Chat setup / Create chat` first. It sends `POST /v1/chats`, validates the create-chat response, and stores `chatId` plus `wsUrl` in the selected environment. Run `Create chat idempotent retry` when you want to verify that the same `(userId, requestId)` and payload return the stored chat with `duplicate = true`.

Create a WebSocket request in Postman with this URL:

```text
{{wsBaseUrl}}/v1/chats/{{chatId}}/ws?clientType={{clientType}}
```

Send the payloads from `ws-messages/` in order:

1. `01-message-submit-initial.json`
2. `02-tool-result-user-ask.json`
3. `03-message-submit-continuation.json`
4. `04-tool-result-open-media.json`

After the first accepted `message.submit` ack, copy `thread.id` into the `threadId` environment variable. After the first `tool.call.started` event, copy `payload.toolCallId` into `toolCallIdAsk`. After the second `tool.call.started` event, copy `payload.toolCallId` into `toolCallIdOpenMedia`.

Expected live sequence:

```text
message.submit -> ack accepted -> thread.status -> tool.call.started user.ask
tool.result -> ack accepted
message.submit -> ack accepted -> thread.status -> tool.call.started device.media.open
tool.result -> ack accepted -> thread.completed
```

Use `GET {{baseUrl}}/v1/chats/{{chatId}}/threads/{{threadId}}?clientType={{clientType}}` to check whether a thread is still alive after reconnects or long gaps without events.

For local backend testing, use the default `baseUrl` and `wsBaseUrl` values. The WebSocket route requires `SOUZ_FEATURE_WS_EVENTS=true`.

Postman exports HTTP collections in the standard collection format. WebSocket requests are configured separately in Postman, so the WebSocket request URL and messages are stored here as portable JSON snippets rather than mixed into the HTTP collection.
