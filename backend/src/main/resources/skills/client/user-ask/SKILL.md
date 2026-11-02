---
name: user-ask
description: Ask the user a concise clarification question over a declared provider’s public Souz WebSocket and wait for their answer. Use when required information is missing or the user's preference must be confirmed.
metadata:
  souz.skill-id: user.ask
  souz.transport: client-websocket
  souz.category: CHAT
  souz.timeout: PT5M
---

# Ask the user

Invoke `RunSkillCommand` with `skillId` set to `user.ask` and a `question` string in `arguments`:

```json
{"skillId":"user.ask","arguments":{"question":"Какие фильмы и жанры тебе нравятся?"}}
```

Souz sends a `tool.call.started` event named `user.ask` to the selected channel’s device and waits up to five minutes for `tool.result`. Use the returned `answer`; do not invent the user's response.

This Skill requires a channel declaration or discovery; it is not injected automatically.
Omit routing `channelId` when the current channel provides it; pass `channelId` in `arguments`
to ask on an explicitly selected other channel.

If the Skill reports missing client context, the selected channel is unavailable.
