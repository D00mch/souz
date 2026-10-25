---
name: orion-tools-list
description: Discover available Orion device functions and their input schemas, including capabilities not yet known to this conversation.
metadata:
  souz.skill-id: orion.tools.list
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# Discover Orion tools

Invoke `RunSkillCommand` with `skillId: "orion.tools.list"` and `arguments: {}`.
Without a current device, use `ListActiveChannels` and add the selected `channelId` to arguments.

The result is `{"tools":[{"name":"...","description":"...","parameters":{...}}]}`,
excluding this discovery tool. Returned tools are immediately available through `GetSkillByName`
and `RunSkillCommand`, bound to the supplying channel and remembered across this conversation's turns.
A successful discovery replaces the previous discovered catalogue; an empty list restores declared and bundled tools.
If the channel is unavailable, select a live channel and discover again.

Discover before delegating, then select the required returned Skill IDs for the child.
The discovery tool itself cannot be delegated. Invalid or failed discovery preserves the previous catalogue.
