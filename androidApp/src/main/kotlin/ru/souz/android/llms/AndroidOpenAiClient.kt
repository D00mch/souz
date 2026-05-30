package ru.souz.android.llms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.storage.AndroidChatMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AndroidOpenAiClient(private val settings: AndroidSettingsProvider) {
    suspend fun respond(messages: List<AndroidChatMessage>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settings.openaiKey?.takeIf { it.isNotBlank() }
                ?: error("OpenAI API key is missing")
            val prompt = messages.takeLast(MAX_CONTEXT_MESSAGES).joinToString("\n\n") { message ->
                "${message.role}: ${message.content}"
            }
            val requestBody = JSONObject()
                .put("model", settings.chatModelAlias)
                .put("input", prompt)
                .toString()

            val connection = (URL(RESPONSES_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = settings.requestTimeoutMillis.toInt()
                readTimeout = settings.requestTimeoutMillis.toInt()
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }

            val responseText = connection.readResponseText()
            if (connection.responseCode !in 200..299) {
                error(parseError(responseText) ?: "OpenAI request failed: HTTP ${connection.responseCode}")
            }
            parseResponse(responseText) ?: error("OpenAI response did not include text output")
        }
    }

    private fun HttpURLConnection.readResponseText(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }
    }

    private fun parseResponse(raw: String): String? {
        val root = JSONObject(raw)
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val output = root.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                block.optString("text").takeIf { it.isNotBlank() }?.let { return it.trim() }
            }
        }
        return null
    }

    private fun parseError(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        const val MAX_CONTEXT_MESSAGES = 20
    }
}
