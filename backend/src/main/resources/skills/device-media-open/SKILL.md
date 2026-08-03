---
name: device-media-open
description: Open media on the user's active client device over the public Souz WebSocket. Use when the user asks to play or open a movie, show, video, song, or other media.
---

# Open media

Invoke `RunSkillCommand` with `skillId` set to `device.media.open`. Pass the media title or search text as `query`; include `genre` only when useful:

```json
{"skillId":"device.media.open","arguments":{"query":"Нечто","genre":"horror"}}
```

Souz sends a durable `tool.call.started` event named `device.media.open` to the active client device and waits up to one minute for `tool.result`. Claim success only when the returned result reports that the media was opened.

If the Skill reports missing client context, no active public WebSocket device is available for this execution.
