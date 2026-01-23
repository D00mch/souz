package ru.gigadesk.tool.meta

import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters

data class ToolGetToolsByCategory(
    private val toolsFactory: ToolsFactory
) : ToolSetup<ToolGetToolsByCategory.Input> {

    data class Input(
        @InputParamDescription("The category to list tools for (e.g., FILES, BROWSER, COMPLEX_TASK)")
        val category: ToolCategory
    )

    override val name: String = "get_tools_by_category"
    override val description: String = "Returns a list of available tools for a given category with their descriptions and arguments."
    override val fewShotExamples: List<FewShotExample> = emptyList()
    override val returnParameters: ReturnParameters = ReturnParameters(properties = emptyMap())

    override fun invoke(input: Input): String {
        val tools: Map<String, GigaToolSetup> = toolsFactory.toolsByCategory[input.category]
            ?: return "Category ${input.category} not found"
        
        val functions: List<GigaRequest.Function> = tools.values.map { it.fn }
        
        // Return structured description of tools in the category
        return functions.joinToString(separator = "\n---\n") { fn ->
            val args = fn.parameters.properties.map { (name, prop) ->
                "$name: ${prop.type} (${prop.description})"
            }.joinToString(", ")
            "Tool: ${fn.name}\nDescription: ${fn.description}\nArgs: $args"
        }
    }
}
