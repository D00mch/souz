package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue

internal val nestedToolSchema = """
    {
      "type":"object",
      "properties":{
        "payload":{
          "type":"object","description":"Batch payload",
          "properties":{
            "records":{
              "type":"array",
              "items":{
                "type":"object",
                "properties":{
                  "kind":{"type":"string","description":"Record kind","enum":["a","b"]},
                  "values":{"type":"array","items":{"type":"array","items":{"type":"integer"}}},
                  "options":{"type":"object","properties":{},"required":[],"additionalProperties":true}
                },
                "required":["kind","values"],"additionalProperties":false
              }
            }
          },
          "required":["records"],"additionalProperties":false
        }
      },
      "required":["payload"],"additionalProperties":false
    }
""".trimIndent()

internal fun nestedSchemaTool() = LLMRequest.Function(
    name = "client.batch",
    description = "Process client records.",
    parameters = restJsonMapper.readValue(nestedToolSchema),
)
