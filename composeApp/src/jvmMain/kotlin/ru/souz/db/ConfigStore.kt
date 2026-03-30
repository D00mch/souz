package ru.souz.db

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProviderImpl.Companion.AI_TUNNEL_KEY
import ru.souz.db.SettingsProviderImpl.Companion.ANTHROPIC_KEY
import ru.souz.db.SettingsProviderImpl.Companion.GIGA_CHAT_KEY
import ru.souz.db.SettingsProviderImpl.Companion.OPENAI_KEY
import ru.souz.db.SettingsProviderImpl.Companion.QWEN_CHAT_KEY
import ru.souz.db.SettingsProviderImpl.Companion.SALUTE_SPEECH_KEY
import ru.souz.giga.gigaJsonMapper
import ru.souz.mcp.OAUTH_STORE_PREFIX
import ru.souz.telemetry.TelemetryStorageKeys
import java.security.SecureRandom
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
            else -> gigaJsonMapper.writeValueAsString(value)
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
                else -> gigaJsonMapper.readValue<T>(str)
            } as T
        }.getOrNull()
    }
}

@PublishedApi
internal object SecretPrefsCodec {
    private val l = LoggerFactory.getLogger(SecretPrefsCodec::class.java)

    private const val ENV_MASTER_KEY = "SOUZ_MASTER_KEY"
    private const val PAYLOAD_PREFIX = "enc:v1:"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
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
        TelemetryStorageKeys.PRIVATE_KEY,
    )

    fun encodeForStorage(key: String, value: String): String {
        if (!isSensitiveKey(key)) return value
        return encrypt(value)
    }

    @PublishedApi
    internal fun decodeForRead(key: String, raw: String, prefs: Preferences): String? {
        if (!isSensitiveKey(key)) return raw

        if (!raw.startsWith(PAYLOAD_PREFIX)) {
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
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)
        val secretKey = deriveKey(masterSecret, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return buildString {
            append(PAYLOAD_PREFIX)
            append(b64(salt))
            append(':')
            append(b64(iv))
            append(':')
            append(b64(cipherText))
        }
    }

    private fun decrypt(payload: String): String {
        val masterSecret = masterSecret()
            ?: throw IllegalStateException("Missing $ENV_MASTER_KEY (env/sysprop) for secret decryption")
        val parts = payload.removePrefix(PAYLOAD_PREFIX).split(':')
        require(parts.size == 3) { "Malformed encrypted payload" }
        val salt = b64Decode(parts[0])
        val iv = b64Decode(parts[1])
        val cipherText = b64Decode(parts[2])
        val secretKey = deriveKey(masterSecret, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val plain = cipher.doFinal(cipherText)
        return plain.toString(Charsets.UTF_8)
    }

    private fun deriveKey(masterSecret: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(masterSecret.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
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
            val encoded = b64(generated)
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

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun b64Decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
}

fun main() {
    ConfigStore.put("abc", 1)
    val result: Int? = ConfigStore.get<Int>("abc")
    println("result $result")
}
