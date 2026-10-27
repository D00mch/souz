package ru.souz.backend.plugins

import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginArgumentsTest {
    @Test fun `validation reports bounded pointers and expectations without values`() {
        val schema = restJsonMapper.readTree("""{
          "type":"object", "required":["repository"], "properties":{
            "repository":{"type":"string"}, "state":{"type":"string","enum":["open","closed"]},
            "items":{"type":"array","items":{"type":"object","required":["number"],"properties":{"number":{"type":"integer"}}}}
          }
        }""")
        val input = restJsonMapper.readTree("""{"repo":"secret repository","state":"secret state","items":[{"number":"secret number"},{"a/b~c":"secret field"}]}""")
        val errors = pluginArgumentErrors(input, schema)
        assertEquals(setOf("/repository", "/repo", "/state", "/items/0/number", "/items/1/number", "/items/1/a~1b~0c"), errors.map { it["path"] }.toSet())
        assertEquals(setOf("required", "unknown_field", "enum_mismatch", "type_mismatch"), errors.map { it["reason"] }.toSet())
        assertFalse(errors.toString().contains("secret"))
        assertTrue(pluginArgumentErrors(restJsonMapper.readTree("""{"repository":"octo/demo","state":"open","items":[{"number":1}]}"""), schema).isEmpty())
        assertEquals(32, pluginArgumentErrors(restJsonMapper.valueToTree((1..100).associate { "unknown$it" to "secret" }), schema).size)
    }
}
