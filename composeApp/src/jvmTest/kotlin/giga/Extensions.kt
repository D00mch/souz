package giga

import io.ktor.client.HttpClient
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.llms.AiTunnelChatAPI
import ru.gigadesk.giga.TokenLogging
import ru.gigadesk.llms.QwenChatAPI

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


private fun zeroUsage() = GigaResponse.Usage(0, 0, 0, 0)
