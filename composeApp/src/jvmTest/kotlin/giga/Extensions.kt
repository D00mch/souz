package giga

import io.ktor.client.HttpClient
import ru.gigadesk.giga.GigaRestChatAPI


fun GigaRestChatAPI.getHttpClient(): HttpClient {
    val field = GigaRestChatAPI::class.java.getDeclaredField("client")
    field.isAccessible = true
    return field.get(this) as HttpClient
}
