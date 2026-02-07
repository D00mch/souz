package giga

import io.ktor.client.HttpClient
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.qwen.QwenChatAPI

fun GigaRestChatAPI.getHttpClient(): HttpClient {
    val field = GigaRestChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun GigaRestChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = GigaRestChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) ?: return zeroUsage()
    return tokenLogging.extractSessionUsage()
}

fun QwenChatAPI.getHttpClient(): HttpClient {
    val field = QwenChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun QwenChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = QwenChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) ?: return zeroUsage()
    return tokenLogging.extractSessionUsage()
}

private fun Any.extractSessionUsage(): GigaResponse.Usage {
    val usageField = runCatching { this.javaClass.getDeclaredField("currentSessionTokensUsage") }
        .getOrNull() ?: return zeroUsage()

    usageField.isAccessible = true
    val usageRef = usageField.get(this) ?: return zeroUsage()
    val loadMethod = runCatching { usageRef.javaClass.getMethod("load") }.getOrNull() ?: return zeroUsage()
    return loadMethod.invoke(usageRef) as? GigaResponse.Usage ?: zeroUsage()
}

private fun zeroUsage() = GigaResponse.Usage(0, 0, 0, 0)
