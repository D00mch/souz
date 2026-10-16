---
name: orion-tools-list
description: Discover all available Orion client tools and their input schemas on a connected device. Use when a task needs an Orion capability missing from the current Skill inventory.
metadata:
  souz.skill-id: orion.tools.list
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# Discover Orion tools

Invoke `RunSkillCommand` with `skillId: "orion.tools.list"` and empty arguments on the
current device. From Telegram, VK, a webhook, or another device, use `ListActiveChannels`
and include the selected device's `channelId` in arguments:

```json
{"skillId":"orion.tools.list","arguments":{"channelId":"<device channel UUID>"}}
```

Success returns `{"tools":[{"name":"...","description":"...","parameters":{"type":"object"}}]}`,
excluding this discovery Skill. Souz registers the returned tools for the current execution.
Inspect their exact names and schemas with `GetSkillByName`, then invoke them through
`RunSkillCommand`. Large results may be stored in Knowledge; use `GetKnowledge` to read them.
The tools stay bound to the channel that supplied them; there is no need to repeat `channelId`.
Discovering another channel replaces this temporary catalogue. New executions must discover again.
Discover in the parent before delegating and select only the needed returned Skills for the child.

Older clients may return `unsupported_tool`; use the existing default client Skills when available.
Do not infer that a tool ran successfully from its presence in the catalogue.
