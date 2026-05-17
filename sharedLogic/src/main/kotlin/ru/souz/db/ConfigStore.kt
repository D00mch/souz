package ru.souz.db

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProviderImpl.Companion.AI_TUNNEL_KEY
import ru.souz.db.SettingsProviderImpl.Companion.ANTHROPIC_KEY
import ru.souz.db.SettingsProviderImpl.Companion.GIGA_CHAT_KEY
import ru.souz.db.SettingsProviderImpl.Companion.OPENAI_KEY
import ru.souz.db.SettingsProviderImpl.Companion.QWEN_CHAT_KEY
import ru.souz.db.SettingsProviderImpl.Companion.SALUTE_SPEECH_KEY
import ru.souz.llms.restJsonMapper
import java.security.SecureRandom
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

object ConfigStore {
    // TODO: move into SettingsProviderImpl
    const val TG_BOT_TOKEN = "TG_BOT_TOKEN"
    const val TG_BOT_OWNER_ID = "TG_BOT_OWNER_ID"
    const val TG_BOT_USERNAME = "TG_BOT_USERNAME"

    const val TG_BOT_TASK_TYPE = "TG_BOT_TASK_TYPE"
    const val TG_BOT_TASK_STEP = "TG_BOT_TASK_STEP"
    const val TG_BOT_TASK_START_MSG_ID = "TG_BOT_TASK_START_MSG_ID"

    @PublishedApi // to use prefs inside the reified (inlined) `get`
    internal val prefs: Preferences = Preferences.userNodeForPackage(ConfigStore::class.java)

    fun put(key: String, value: Any) {
        val str = when (value) {
            is String -> value
            is Int, is Long, is Float, is Double, is Boolean -> value.toString()
            else -> restJsonMapper.writeValueAsString(value)
        }
        prefs.put(key, SecretPrefsCodec.encodeForStorage(key, str))
    }

    @Suppress("unused")
    fun rm(key: String) {
        prefs.remove(key)
    }

    inline fun <reified T : Any> get(key: String, default: T): T =
        get<T>(key) ?: default

    inline fun <reified T : Any> get(key: String): T? {
        val raw = prefs.get(key, null) ?: return null
        val str = SecretPrefsCodec.decodeForRead(key, raw, prefs) ?: return null
        return runCatching {
            when (T::class) {
                Int::class -> str.toInt()
                Long::class -> str.toLong()
                Float::class -> str.toFloat()
                Double::class -> str.toDouble()
                Boolean::class -> str.toBooleanStrict()
                String::class -> str
                else -> restJsonMapper.readValue<T>(str)
            } as T
        }.getOrNull()
    }
}

@PublishedApi
internal object SecretPrefsCodec {
    private val l = LoggerFactory.getLogger(SecretPrefsCodec::class.java)

    private const val ENV_MASTER_KEY = "SOUZ_MASTER_KEY"
    private const val LOCAL_APP_DIR_NAME = "Souz"
    private const val LOCAL_MASTER_KEY_FILE = "master.key"
    private val secureRandom = SecureRandom()
    private val cachedLocalMasterSecret = AtomicReference<String?>(null)
    private val localMasterSecretLock = Any()

    private val exactSensitiveKeys = setOf(
        ConfigStore.TG_BOT_TOKEN,
        GIGA_CHAT_KEY,
        QWEN_CHAT_KEY,
        AI_TUNNEL_KEY,
        ANTHROPIC_KEY,
        OPENAI_KEY,
        SALUTE_SPEECH_KEY,
        TELEMETRY_PRIVATE_KEY,
    )

    fun encodeForStorage(key: String, value: String): String {
        if (!isSensitiveKey(key)) return value
        return encrypt(value)
    }

    @PublishedApi
    internal fun decodeForRead(key: String, raw: String, prefs: Preferences): String? {
        if (!isSensitiveKey(key)) return raw

        if (!AesGcmSecretCodec.isEncrypted(raw)) {
            // Transparent migration for legacy plaintext values once a master key is configured.
            val readyForMigration = runCatching { masterSecret() != null }
                .onFailure { e ->
                    l.warn("Failed to initialize local master key for secret migration: {}", e.message)
                }
                .getOrDefault(false)
            if (readyForMigration) {
                runCatching { prefs.put(key, encrypt(raw)) }
                    .onFailure { e -> l.warn("Failed to migrate secret {} to encrypted storage: {}", key, e.message) }
            } else {
                l.warn(
                    "Secret {} is stored in plaintext. Encrypted storage is unavailable ({} override or local key file).",
                    key,
                    ENV_MASTER_KEY,
                )
            }
            return raw
        }

        return runCatching { decrypt(raw) }
            .onFailure { e ->
                l.warn(
                    "Failed to decrypt secret {} (check {} override or local key file). Returning null: {}",
                    key,
                    ENV_MASTER_KEY,
                    e.message,
                )
            }
            .getOrNull()
    }

    private fun isSensitiveKey(key: String): Boolean =
        key in exactSensitiveKeys || key.startsWith(OAUTH_STORE_PREFIX)

    private fun encrypt(plainText: String): String {
        val masterSecret = masterSecret()
            ?: throw IllegalStateException("Missing $ENV_MASTER_KEY (env/sysprop) for secret storage")
        return AesGcmSecretCodec.encrypt(masterKey = masterSecret, plainText = plainText)
    }

    private fun decrypt(payload: String): String {
        val masterSecret = masterSecret()
            ?: throw IllegalStateException("Missing $ENV_MASTER_KEY (env/sysprop) for secret decryption")
        return AesGcmSecretCodec.decrypt(masterKey = masterSecret, payload = payload)
    }

    private fun masterSecret(): String? =
        System.getenv(ENV_MASTER_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getProperty(ENV_MASTER_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: localMasterSecret()

    private fun localMasterSecret(): String {
        cachedLocalMasterSecret.get()?.let { return it }
        return synchronized(localMasterSecretLock) {
            cachedLocalMasterSecret.get()?.let { return@synchronized it }
            val loaded = loadOrCreateLocalMasterSecret()
            cachedLocalMasterSecret.set(loaded)
            loaded
        }
    }

    private fun loadOrCreateLocalMasterSecret(): String {
        val keyPath = localMasterKeyPath()
        val parent = keyPath.parent ?: throw IllegalStateException("Invalid local master key path: $keyPath")
        Files.createDirectories(parent)

        if (!Files.exists(keyPath)) {
            val generated = ByteArray(32).also(secureRandom::nextBytes)
            val encoded = java.util.Base64.getEncoder().encodeToString(generated)
            try {
                Files.writeString(
                    keyPath,
                    encoded + "\n",
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                hardenLocalKeyFilePermissions(keyPath)
                l.info("Generated local master key for secret storage at {}", keyPath)
                return encoded
            } catch (_: FileAlreadyExistsException) {
                // Lost a race to another thread/process; fall through to read.
            }
        }

        val value = Files.readString(keyPath).trim()
        require(value.isNotEmpty()) { "Local master key file is empty: $keyPath" }
        return value
    }

    private fun localMasterKeyPath(): Path {
        val userHome = System.getProperty("user.home")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("user.home is not set")
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("mac") -> Path.of(
                userHome,
                "Library",
                "Application Support",
                LOCAL_APP_DIR_NAME,
                LOCAL_MASTER_KEY_FILE,
            )

            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                if (appData != null) {
                    Path.of(appData, LOCAL_APP_DIR_NAME, LOCAL_MASTER_KEY_FILE)
                } else {
                    Path.of(userHome, "AppData", "Roaming", LOCAL_APP_DIR_NAME, LOCAL_MASTER_KEY_FILE)
                }
            }

            else -> Path.of(userHome, ".config", LOCAL_APP_DIR_NAME.lowercase(), LOCAL_MASTER_KEY_FILE)
        }
    }

    private fun hardenLocalKeyFilePermissions(path: Path) {
        val file = path.toFile()
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

}
    private const val OAUTH_STORE_PREFIX = "MCP_OAUTH_STATE_"
    private const val TELEMETRY_PRIVATE_KEY = "TELEMETRY_PRIVATE_KEY"
