package example

import ru.souz.plugins.api.PluginTool
import ru.souz.plugins.api.SouzPlugin

class HelloPlugin : SouzPlugin {
    override val tools = listOf(PluginTool(
        name = "hello",
        description = "Return a greeting from a JVM plugin.",
        inputSchemaJson = """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
        category = "CALCULATOR",
    ) { _, context ->
        context.log("Greeting requested")
        """{"message":"Hello from a JVM plugin"}"""
    })
}
