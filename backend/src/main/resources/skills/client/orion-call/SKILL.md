---
name: orion-call
description: Default tool for music, playback, volume, timers and alarms through Orion voice commands on a connected device. Resolve indirect music references before calling; returns Orion's spoken reply.
metadata:
  souz.skill-id: orion.call
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT1M
---

# Orion voice commands

Use `RunSkillCommand` with `skillId: "orion.call"` and a nonblank `utterance` in the user's language:

```json
{"skillId":"orion.call","arguments":{"utterance":"включи Pink Floyd"}}
```

Use this Skill for music, playback (stop, next/previous, volume), timers and alarms.
Do not probe `device.mcp.*` first: those tools expose OS/app functions, not Orion voice intents.
Pass direct commands close to verbatim. Resolve indirect music references (a show's theme,
a lyric, a song from an advert) to an artist and title first, using search when uncertain.
Then send a direct command such as "включи <artist> — <title>". Ask for clarification if unresolved.

Success returns `{"reply":"Включаю Pink Floyd"}`. Relay that outcome without inventing confirmation;
an unrecognized command may return an apology as an ordinary reply. Transport failures use
`orion_call_failed` or `client_tool_timed_out`; the timeout is one minute.
