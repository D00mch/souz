---
name: client-websocket
description: Send a request to the user's active client device over the public Souz WebSocket and wait for its tool.result response. Use for clarification questions and client-side actions such as opening media.
---

# Client WebSocket

Invoke `RunSkillCommand` with `skillId` set to `client.websocket`. Pass these fields in its `arguments` object:

- `name`: the client operation name.
- `arguments`: the operation-specific JSON object.
- `timeoutSeconds`: optional response deadline from 1 to 300 seconds; default 300.

Souz sends a durable `tool.call.started` event whose payload contains the operation `name`, selected `deviceId`, `arguments`, and `deadlineAt`. The call waits for the client to reply with `tool.result`; use the returned JSON as the user or device response. Do not claim success before a successful result arrives.

Use `user.ask` to ask a concise clarification question:

```json
{"skillId":"client.websocket","arguments":{"name":"user.ask","arguments":{"question":"Какие фильмы и жанры тебе нравятся?"}}}
```

Use namespaced device operations such as `device.media.open` for client-side actions:

```json
{"skillId":"client.websocket","arguments":{"name":"device.media.open","arguments":{"query":"Нечто","genre":"horror"},"timeoutSeconds":60}}
```

If the Skill reports missing client context, no active public WebSocket device is available for this execution.
