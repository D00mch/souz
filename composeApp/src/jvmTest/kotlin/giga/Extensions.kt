package giga

import io.ktor.client.HttpClient
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaRestChatAPI
import ru.souz.llms.AiTunnelChatAPI
import ru.souz.llms.AnthropicChatAPI
import ru.souz.giga.TokenLogging
import ru.souz.llms.OpenAIChatAPI
import ru.souz.llms.QwenChatAPI

fun GigaRestChatAPI.getHttpClient(): HttpClient {
    val field = GigaRestChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun GigaRestChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = GigaRestChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) as? TokenLogging ?: return zeroUsage()
    return tokenLogging.sessionTokenUsage()
}

fun QwenChatAPI.getHttpClient(): HttpClient {
    val field = QwenChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun QwenChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = QwenChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) as? TokenLogging ?: return zeroUsage()
    return tokenLogging.sessionTokenUsage()
}

fun AiTunnelChatAPI.getHttpClient(): HttpClient {
    val field = AiTunnelChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun AiTunnelChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = AiTunnelChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) as? TokenLogging ?: return zeroUsage()
    return tokenLogging.sessionTokenUsage()
}

fun AnthropicChatAPI.getHttpClient(): HttpClient {
    val field = AnthropicChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun AnthropicChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = AnthropicChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) as? TokenLogging ?: return zeroUsage()
    return tokenLogging.sessionTokenUsage()
}

fun OpenAIChatAPI.getHttpClient(): HttpClient {
    val field = OpenAIChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun OpenAIChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val tokenLoggingField = OpenAIChatAPI::class.java.getDeclaredField("tokenLogging")
    tokenLoggingField.isAccessible = true
    val tokenLogging = tokenLoggingField.get(this) as? TokenLogging ?: return zeroUsage()
    return tokenLogging.sessionTokenUsage()
}


private fun zeroUsage() = GigaResponse.Usage(0, 0, 0, 0)
