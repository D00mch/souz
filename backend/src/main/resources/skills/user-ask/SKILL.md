---
name: user-ask
description: Ask the user a concise clarification question over the active public Souz WebSocket and wait for their answer. Use when required information is missing or the user's preference must be confirmed.
---

# Ask the user

Invoke `RunSkillCommand` with `skillId` set to `user.ask` and a `question` string in `arguments`:

```json
{"skillId":"user.ask","arguments":{"question":"Какие фильмы и жанры тебе нравятся?"}}
```

Souz sends a durable `tool.call.started` event named `user.ask` to the active client device and waits up to five minutes for `tool.result`. Use the returned `answer`; do not invent the user's response.

If the Skill reports missing client context, no active public WebSocket device is available for this execution.
