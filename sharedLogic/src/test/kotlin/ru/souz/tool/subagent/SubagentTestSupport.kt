package ru.souz.tool.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.agent.SubagentTool
import ru.souz.agent.runtime.withSubagentTasks
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

internal suspend fun SubagentTool.spawnAndWait(
    call: LLMResponse.FunctionCall,
    meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
) = withContext(Dispatchers.Default) { withSubagentTasks {
    val spawned = invoke(call, meta)
    val id = restJsonMapper.readTree(spawned.content)["subagentId"] ?: return@withSubagentTasks spawned
    tools.single { it.fn.name == SubagentTool.WAIT }.invoke(LLMResponse.FunctionCall(SubagentTool.WAIT,
        mapOf("subagentId" to id.asText(), "timeoutMs" to 30000)), meta)
} }
