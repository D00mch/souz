@file:OptIn(ExperimentalAtomicApi::class)

package giga

import io.ktor.client.HttpClient
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.qwen.QwenChatAPI
import kotlin.concurrent.atomics.ExperimentalAtomicApi


fun GigaRestChatAPI.getHttpClient(): HttpClient {
    val field = GigaRestChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun GigaRestChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val field = GigaRestChatAPI::class.java.getDeclaredField("currentSessionTokensUsage")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(this) as kotlin.concurrent.atomics.AtomicReference<GigaResponse.Usage>).load()
}

fun QwenChatAPI.getHttpClient(): HttpClient {
    val field = QwenChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}

fun QwenChatAPI.getSessionTokenUsage(): GigaResponse.Usage {
    val field = QwenChatAPI::class.java.getDeclaredField("currentSessionTokensUsage")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(this) as kotlin.concurrent.atomics.AtomicReference<GigaResponse.Usage>).load()
}