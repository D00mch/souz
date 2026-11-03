---
name: orion-tools-list
description: Discover tools and their input schemas supplied by a client channel.
metadata:
  souz.skill-id: orion.tools.list
  souz.transport: client-websocket
  souz.category: APPLICATIONS
  souz.timeout: PT30S
---

# Discover channel tools

Invoke `RunSkillCommand` with `skillId: "orion.tools.list"` and `arguments: {}`.
The current conversation channel is the default. For another device, select its `channelId` from the providing channels and include it in arguments.

The result is `{"tools":[{"name":"...","description":"...","parameters":{...}}]}`,
excluding this discovery tool. Returned tools are immediately available through `GetSkillByName`
and `RunSkillCommand` across this user's conversations. Discovery replaces only the supplying
channel's discovered catalogue; an empty list restores that channel's declarations.
Equal definitions share providers; conflicting definitions and server control commands are hidden.
Catalogues survive backend restarts. Discover an unknown intended channel before rejecting a capability
or asking which device for a local request. Do not rediscover solely because a tool is absent from a
completed catalogue; refresh only on request or when capabilities change.

Discover before delegating, then select the required returned Skill IDs for the child.
The discovery tool itself cannot be delegated. Invalid or failed discovery preserves the previous catalogue.
