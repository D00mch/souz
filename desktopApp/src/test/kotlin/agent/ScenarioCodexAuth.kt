package agent

import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.readText

/** Uses the opted-in local login without giving tests ownership of its refresh token or storage. */
internal fun scenarioCodexSettings(
    settings: SettingsProvider,
    authFile: Path = Path.of(
        System.getenv("SOUZ_AGENT_INTEGRATION_CODEX_AUTH_FILE")
            ?: Path.of(System.getenv("CODEX_HOME") ?: "${System.getProperty("user.home")}/.codex", "auth.json").toString(),
    ),
    now: Instant = Instant.now(),
): SettingsProvider {
    val tokens = try {
        restJsonMapper.readTree(authFile.readText()).path("tokens")
    } catch (_: Exception) {
        error("Cannot read Codex login at $authFile. Sign in locally before running Codex scenarios.")
    }
    val accessToken = tokens.path("access_token").asText("")
    val accountId = tokens.path("account_id").asText("")
    require(accessToken.isNotBlank() && accountId.isNotBlank()) {
        "Codex login at $authFile must contain an access token and account ID."
    }
    val expiresAt = try {
        restJsonMapper.readTree(Base64.getUrlDecoder().decode(accessToken.split('.')[1])).path("exp").asLong()
    } catch (_: Exception) {
        error("Codex login at $authFile has an invalid access-token expiry.")
    }
    require(expiresAt > now.epochSecond + 300) {
        "Codex login at $authFile is expired or expires soon. Refresh it through Codex before rerunning."
    }
    return object : SettingsProvider by settings {
        override var codexAccessToken: String? = accessToken
        override var codexAccountId: String? = accountId
        override var codexRefreshToken: String? = null
        override var codexExpiresAt: Long? = expiresAt
    }
}
