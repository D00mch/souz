package ru.souz.backend.client

import java.util.UUID

internal fun clientToolDiscoveryRequired(channelId: UUID): ClientError = ClientError(
    "client_tool_discovery_required",
    "Channel $channelId has no completed discovery catalogue and has not declared this tool. " +
        "The parent must invoke RunSkillCommand with skillId=orion.tools.list and arguments={\"channelId\":\"$channelId\"}, " +
        "then retry on this same channel if discovery returns the tool. " +
        "If discovery confirms absence, report that capability as unavailable. " +
        "Do not ask which device for a local request or silently switch devices.",
)
