package ru.souz.tool.skills

import com.fasterxml.jackson.databind.JsonNode

/** Discovery schemas that cannot be represented by the compiled-tool parameter subset. */
interface SkillToolSchemas {
    val skillInputSchema: JsonNode
    val skillReturnSchema: JsonNode?
}
