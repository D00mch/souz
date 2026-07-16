package ru.souz.android.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSettingsProvider(context: Context) : SettingsProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretCodec = AndroidSecretCodec()

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? =
        string("${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}")

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        putString("${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}", prompt?.takeIf { it.isNotBlank() })
    }

    override var gigaChatKey: String?
        get() = secretString(GIGA_CHAT_KEY)
        set(value) = putSecretString(GIGA_CHAT_KEY, value)

    override var qwenChatKey: String?
        get() = secretString(QWEN_CHAT_KEY)
        set(value) = putSecretString(QWEN_CHAT_KEY, value)

    override var aiTunnelKey: String?
        get() = secretString(AI_TUNNEL_KEY)
        set(value) = putSecretString(AI_TUNNEL_KEY, value)

    override var anthropicKey: String?
        get() = secretString(ANTHROPIC_KEY)
        set(value) = putSecretString(ANTHROPIC_KEY, value)

    override var openaiKey: String?
        get() = secretString(OPENAI_KEY)
        set(value) = putSecretString(OPENAI_KEY, value)

    override var codexAccessToken: String?
        get() = secretString(CODEX_ACCESS_TOKEN)
        set(value) = putSecretString(CODEX_ACCESS_TOKEN, value)

    override var codexRefreshToken: String?
        get() = secretString(CODEX_REFRESH_TOKEN)
        set(value) = putSecretString(CODEX_REFRESH_TOKEN, value)

    override var codexAccountId: String?
        get() = secretString(CODEX_ACCOUNT_ID)
        set(value) = putSecretString(CODEX_ACCOUNT_ID, value)

    override var codexExpiresAt: Long?
        get() = secretString(CODEX_EXPIRES_AT)?.toLongOrNull()
        set(value) = putSecretString(CODEX_EXPIRES_AT, value?.toString())

    override var saluteSpeechKey: String?
        get() = secretString(SALUTE_SPEECH_KEY)
        set(value) = putSecretString(SALUTE_SPEECH_KEY, value)

    override fun hasKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> hasSecret(GIGA_CHAT_KEY)
        LlmProvider.QWEN -> hasSecret(QWEN_CHAT_KEY)
        LlmProvider.AI_TUNNEL -> hasSecret(AI_TUNNEL_KEY)
        LlmProvider.ANTHROPIC -> hasSecret(ANTHROPIC_KEY)
        LlmProvider.OPENAI -> hasSecret(OPENAI_KEY)
        LlmProvider.LOCAL -> true
        LlmProvider.CODEX -> hasSecret(CODEX_ACCESS_TOKEN)
    }

    override fun hasKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> hasSecret(SALUTE_SPEECH_KEY)
        VoiceRecognitionProvider.AI_TUNNEL -> hasSecret(AI_TUNNEL_KEY)
        VoiceRecognitionProvider.OPENAI -> hasSecret(OPENAI_KEY)
        VoiceRecognitionProvider.LOCAL_MACOS -> true
    }

    override var supportEmail: String?
        get() = string(SUPPORT_EMAIL)
        set(value) = putString(SUPPORT_EMAIL, value)

    override var defaultCalendar: String?
        get() = string(DEFAULT_CALENDAR)
        set(value) = putString(DEFAULT_CALENDAR, value)

    override var regionProfile: String
        get() {
            val raw = string(APP_LANGUAGE)?.trim()?.lowercase()
            val normalized = if (raw == REGION_RU) REGION_RU else REGION_EN
            if (raw != normalized) putString(APP_LANGUAGE, normalized)
            return normalized
        }
        set(value) {
            putString(APP_LANGUAGE, if (value.trim().lowercase() == REGION_RU) REGION_RU else REGION_EN)
        }

    override var activeAgentId: AgentId
        get() = AgentId.fromStorageValue(string(ACTIVE_AGENT_ID))
        set(value) = putString(ACTIVE_AGENT_ID, value.storageValue)

    override var gigaModel: LLMModel
        get() = modelFromAlias(chatModelAlias) ?: defaultLlmModel()
        set(value) {
            chatModelAlias = value.alias
        }

    override var ambientAnalysisModel: LLMModel
        get() = string(AMBIENT_ANALYSIS_MODEL)
            ?.let(::modelFromAlias)
            ?.takeIf { it.provider == LlmProvider.LOCAL }
            ?: defaultAmbientAnalysisModel()
        set(value) {
            putString(AMBIENT_ANALYSIS_MODEL, value.takeIf { it.provider == LlmProvider.LOCAL }?.alias)
        }

    var chatModelAlias: String
        get() = string(CHAT_MODEL_ALIAS)?.takeIf { it.isNotBlank() } ?: defaultLlmModel().alias
        set(value) = putString(CHAT_MODEL_ALIAS, value.ifBlank { defaultLlmModel().alias })

    override var useFewShotExamples: Boolean
        get() = boolean(USE_FEW_SHOTS, false)
        set(value) = putBoolean(USE_FEW_SHOTS, value)

    override var useStreaming: Boolean
        get() = boolean(USE_STREAMING, false)
        set(value) = putBoolean(USE_STREAMING, value)

    override var notificationSoundEnabled: Boolean
        get() = boolean(NOTIFICATION_SOUND_ENABLED, true)
        set(value) = putBoolean(NOTIFICATION_SOUND_ENABLED, value)

    override var voiceInputReviewEnabled: Boolean
        get() = boolean(VOICE_INPUT_REVIEW_ENABLED, false)
        set(value) = putBoolean(VOICE_INPUT_REVIEW_ENABLED, value)

    override var safeModeEnabled: Boolean
        get() = boolean(SAFE_MODE_ENABLED, true)
        set(value) = putBoolean(SAFE_MODE_ENABLED, value)

    override var needsOnboarding: Boolean
        get() = boolean(NEEDS_ONBOARDING, false)
        set(value) = putBoolean(NEEDS_ONBOARDING, value)

    override var onboardingCompleted: Boolean
        get() = boolean(ONBOARDING_COMPLETED, false)
        set(value) = putBoolean(ONBOARDING_COMPLETED, value)

    override var requestTimeoutMillis: Long
        get() = long(REQUEST_TIMEOUT_MILLIS, 40_000L)
        set(value) = putLong(REQUEST_TIMEOUT_MILLIS, value)

    override var contextSize: Int
        get() = int(CONTEXT_SIZE, DEFAULT_MAX_TOKENS).takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
        set(value) = putInt(CONTEXT_SIZE, value.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS)

    override var initialWindowWidthDp: Int
        get() = int(INITIAL_WINDOW_WIDTH_DP, 580)
        set(value) = putInt(INITIAL_WINDOW_WIDTH_DP, value)

    override var initialWindowHeightDp: Int
        get() = int(INITIAL_WINDOW_HEIGHT_DP, 780)
        set(value) = putInt(INITIAL_WINDOW_HEIGHT_DP, value)

    override var temperature: Float
        get() = float(TEMPERATURE, 0.7f)
        set(value) = putFloat(TEMPERATURE, value)

    override var forbiddenFolders: List<String>
        get() = string(FORBIDDEN_FOLDERS)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        set(value) = putString(
            FORBIDDEN_FOLDERS,
            value.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"),
        )

    override var embeddingsModel: EmbeddingsModel
        get() = string(EMBEDDINGS_MODEL)?.let(::embeddingsModelFromAlias)
            ?: EmbeddingsModel.OpenAITextEmbedding3Small
        set(value) = putString(EMBEDDINGS_MODEL, value.alias)

    override var voiceRecognitionModel: VoiceRecognitionModel
        get() = string(VOICE_RECOGNITION_MODEL)?.let(::voiceRecognitionModelFromAlias)
            ?: VoiceRecognitionModel.OpenAIGpt4oMiniTranscribe
        set(value) = putString(VOICE_RECOGNITION_MODEL, value.alias)

    override var mcpServersJson: String?
        get() = string(MCP_SERVERS_JSON)
        set(value) = putString(MCP_SERVERS_JSON, value)

    override var mcpServersFile: String?
        get() = string(MCP_SERVERS_FILE)
        set(value) = putString(MCP_SERVERS_FILE, value)

    private fun defaultLlmModel(): LLMModel {
        val defaults = LlmBuildProfile.defaultsForLanguage(regionProfile)
        return LlmBuildProfile.providerPrioritiesForLanguage(regionProfile)
            .firstNotNullOfOrNull { provider -> defaults[provider]?.takeIf { hasConfiguredAccess(provider) } }
            ?: LLMModel.OpenAIGpt5Nano
    }

    private fun defaultAmbientAnalysisModel(): LLMModel =
        LLMModel.LocalQwen3_4B_Instruct_2507

    private fun hasConfiguredAccess(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.LOCAL -> false
        else -> hasKey(provider)
    }

    private fun modelFromAlias(value: String): LLMModel? =
        LLMModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

    private fun embeddingsModelFromAlias(value: String): EmbeddingsModel? =
        EmbeddingsModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

    private fun voiceRecognitionModelFromAlias(value: String): VoiceRecognitionModel? =
        VoiceRecognitionModel.entries.firstOrNull { it.alias.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }

    private fun string(key: String): String? = prefs.getString(key, null)

    private fun secretString(key: String): String? =
        prefs.getString(key, null)?.let(secretCodec::decrypt)

    private fun hasSecret(key: String): Boolean = !prefs.getString(key, null).isNullOrBlank()

    private fun boolean(key: String, default: Boolean): Boolean =
        prefs.getString(key, null)?.toBooleanStrictOrNull() ?: default

    private fun int(key: String, default: Int): Int =
        prefs.getString(key, null)?.toIntOrNull() ?: default

    private fun long(key: String, default: Long): Long =
        prefs.getString(key, null)?.toLongOrNull() ?: default

    private fun float(key: String, default: Float): Float =
        prefs.getString(key, null)?.toFloatOrNull() ?: default

    private fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        }.apply()
    }

    private fun putSecretString(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, secretCodec.encrypt(value.trim()))
        }.apply()
    }

    private fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    private fun putInt(key: String, value: Int) = putString(key, value.toString())
    private fun putLong(key: String, value: Long) = putString(key, value.toString())
    private fun putFloat(key: String, value: Float) = putString(key, value.toString())

    private companion object {
        const val PREFS_NAME = "souz_android_settings"
        const val GIGA_CHAT_KEY = "GIGA_CHAT_KEY"
        const val QWEN_CHAT_KEY = "QWEN_CHAT_KEY"
        const val AI_TUNNEL_KEY = "AI_TUNNEL_KEY"
        const val ANTHROPIC_KEY = "ANTHROPIC_KEY"
        const val OPENAI_KEY = "OPENAI_KEY"
        const val CODEX_ACCESS_TOKEN = "CODEX_ACCESS_TOKEN"
        const val CODEX_REFRESH_TOKEN = "CODEX_REFRESH_TOKEN"
        const val CODEX_ACCOUNT_ID = "CODEX_ACCOUNT_ID"
        const val CODEX_EXPIRES_AT = "CODEX_EXPIRES_AT"
        const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
        const val APP_LANGUAGE = "APP_LANGUAGE"
        const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        const val USE_STREAMING = "USE_STREAMING"
        const val NOTIFICATION_SOUND_ENABLED = "NOTIFICATION_SOUND_ENABLED"
        const val VOICE_INPUT_REVIEW_ENABLED = "VOICE_INPUT_REVIEW_ENABLED"
        const val SAFE_MODE_ENABLED = "SAFE_MODE_ENABLED"
        const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        const val ACTIVE_AGENT_ID = "ACTIVE_AGENT_ID"
        const val DEFAULT_CALENDAR = "DEFAULT_CALENDAR"
        const val CHAT_MODEL_ALIAS = "CHAT_MODEL_ALIAS"
        const val AMBIENT_ANALYSIS_MODEL = "AMBIENT_ANALYSIS_MODEL"
        const val NEEDS_ONBOARDING = "NEEDS_ONBOARDING"
        const val ONBOARDING_COMPLETED = "ONBOARDING_COMPLETED"
        const val REQUEST_TIMEOUT_MILLIS = "REQUEST_TIMEOUT_MILLIS"
        const val CONTEXT_SIZE = "CONTEXT_SIZE"
        const val INITIAL_WINDOW_WIDTH_DP = "INITIAL_WINDOW_WIDTH_DP"
        const val INITIAL_WINDOW_HEIGHT_DP = "INITIAL_WINDOW_HEIGHT_DP"
        const val TEMPERATURE = "TEMPERATURE"
        const val FORBIDDEN_FOLDERS = "FORBIDDEN_FOLDERS"
        const val EMBEDDINGS_MODEL = "EMBEDDINGS_MODEL"
        const val VOICE_RECOGNITION_MODEL = "VOICE_RECOGNITION_MODEL"
        const val MCP_SERVERS_JSON = "MCP_SERVERS_JSON"
        const val MCP_SERVERS_FILE = "MCP_SERVERS_FILE"
        const val REGION_RU = "ru"
        const val REGION_EN = "en"
    }
}

private class AndroidSecretCodec {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? = runCatching {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_BYTES)
        val iv = payload.copyOfRange(0, IV_BYTES)
        val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "souz_android_settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
