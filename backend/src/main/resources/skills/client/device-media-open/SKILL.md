---
name: device-media-open
description: Open media on the selected channel’s device over the public Souz WebSocket. Use for movies, shows and video.
metadata:
  souz.skill-id: device.media.open
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Open media

Invoke `RunSkillCommand` with `skillId` set to `device.media.open`. Pass the media title or search text as `query`; include `genre` only when useful:

```json
{"skillId":"device.media.open","arguments":{"channelId":"<provider-chat-uuid>","query":"Нечто","genre":"horror"}}
```

Omit routing `channelId` to use the current channel. For an explicitly selected other device,
pass its provider’s `channelId` in `arguments`.

Souz sends a `tool.call.started` event named `device.media.open` to the selected channel’s device and waits up to one minute for `tool.result`. Claim success only when the returned result reports that the media was opened.

If the Skill reports missing client context, the selected channel is unavailable.
