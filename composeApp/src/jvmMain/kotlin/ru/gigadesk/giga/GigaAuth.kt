package ru.gigadesk.giga

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

object GigaAuth {
    private val l = LoggerFactory.getLogger(GigaAuth::class.java)
    suspend fun requestToken(apiKey: String, scope: String): String {
        val client = HttpClient(CIO) {
            gigaDefaults()
        }
        val response = client.submitForm(
            url = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
            formParameters = Parameters.build {
                append("scope", scope)
            }
        ) {
            header("Content-Type", "application/x-www-form-urlencoded")
            header("Authorization", "Basic $apiKey")
        }

        if (!response.status.isSuccess()) {
            l.error("Error in requestToken: ${response.status}")
            throw IllegalStateException("Error in requestToken: ${response.status}")
        }
        val token = try {
            response.body<GigaResponse.Token>()
        } catch (e: Exception) {
            l.error("Error in requestToken: ${e.message}")
            throw e
        }
        client.close()
        return token.accessToken
    }
}
/*
curl --location 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth' \
--header 'RqUID: 6f0b1291-c7f3-43c6-bb2e-9f3efb2dc98e' \
--header 'Content-Type: application/x-www-form-urlencoded' \
--header 'Authorization: Basic NzkyMDJiYWMtMmQ1ZC00OGVhLWFhZGQtZTNlNGU4ZDE5YjMyOjEwOGNlMjhkLTM0MzAtNDE1MC1iZTU1LTZkMDNlMTNlZmU5Mg==' \
--data-urlencode 'scope=SALUTE_SPEECH_PERS'
 */