package ru.souz.llms.json

import com.fasterxml.jackson.databind.ObjectMapper

class JsonUtils(private val objectMapper: ObjectMapper) {
    
    /**
     * Extracts the first valid JSON object from raw text.
     *
     * Scans [text] for balanced `{...}` regions, validates each candidate with the
     * configured [ObjectMapper], and returns the first candidate that parses as a JSON
     * object. Surrounding prose or Markdown fences are ignored.
     *
     * @throws IllegalArgumentException if [text] does not contain a valid JSON object.
     */
    fun extractObject(text: String): String {
        val trimmed = text.trim()
        for (candidate in findBalancedJsonObjects(trimmed)) {
            val isValidJsonObject = runCatching {
                objectMapper.readTree(candidate).isObject
            }.getOrDefault(false)

            if (isValidJsonObject) return candidate
        }
        throw IllegalArgumentException("Expected JSON object in LLM output.")
    }

    private fun findBalancedJsonObjects(text: String): Sequence<String> = sequence {
        for (start in text.indices) {
            if (text[start] != '{') continue

            val end = findMatchingClosingBrace(text, start)
            if (end != -1) {
                yield(text.substring(start, end + 1).trim())
            }
        }
    }

    private fun findMatchingClosingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]

            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }

        return -1
    }
}