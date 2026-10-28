package example

import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.PluginTool
import ru.souz.plugins.SouzPlugin

class HelloPlugin : SouzPlugin {
    override val tools = listOf(PluginTool(
        LLMRequest.Function("greet", "Return a greeting", LLMRequest.Parameters("object", additionalProperties = false)),
        "HELP",
    ) { _, context -> restJsonMapper.createObjectNode().put("greeting", "Hello").put("locale", context.meta.locale) })
}
