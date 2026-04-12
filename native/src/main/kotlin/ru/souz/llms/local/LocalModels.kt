package ru.souz.llms.local

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import org.slf4j.LoggerFactory
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LocalModelAvailability

data class LocalLicenseRequirements(
    val summary: String,
    val requiresManualAcceptance: Boolean = false,
)

interface LocalDownloadableProfile {
    val id: String
    val displayName: String
    val huggingFaceRepoId: String
    val ggufFilename: String
    val quantization: String
    val minRamGb: Int
    val licenseRequirements: LocalLicenseRequirements
    val defaultGpuLayers: Int

    val downloadUrl: String
        get() = "https://huggingface.co/$huggingFaceRepoId/resolve/main/$ggufFilename?download=true"
}

enum class LocalPromptFamily {
    QWEN_CHATML,
    GEMMA4,
}

data class LocalSamplingDefaults(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
)

data class LocalModelProfile(
    val gigaModel: LLMModel,
    override val id: String,
    override val displayName: String,
    override val huggingFaceRepoId: String,
    override val ggufFilename: String,
    override val quantization: String,
    override val minRamGb: Int,
    val defaultContextSize: Int,
    val maxContextSize: Int = defaultContextSize,
    val promptFamily: LocalPromptFamily,
    val samplingDefaults: LocalSamplingDefaults,
    val useNativeGrammar: Boolean = false,
    override val licenseRequirements: LocalLicenseRequirements,
    override val defaultGpuLayers: Int = 99,
) : LocalDownloadableProfile

enum class LocalEmbeddingInputKind {
    QUERY,
    DOCUMENT,
}

data class LocalEmbeddingProfile(
    val embeddingsModel: EmbeddingsModel,
    override val id: String,
    override val displayName: String,
    override val huggingFaceRepoId: String,
    override val ggufFilename: String,
    override val quantization: String,
    override val minRamGb: Int,
    val maxContextSize: Int,
    val outputDimensions: Int,
    val queryPrefix: String,
    val documentPrefix: String,
    override val licenseRequirements: LocalLicenseRequirements,
    override val defaultGpuLayers: Int = 99,
) : LocalDownloadableProfile {
    fun format(text: String, inputKind: LocalEmbeddingInputKind): String = when (inputKind) {
        LocalEmbeddingInputKind.QUERY -> "$queryPrefix$text"
        LocalEmbeddingInputKind.DOCUMENT -> "$documentPrefix$text"
    }
}

enum class LocalPlatform(
    val resourceDirectory: String,
    val libraryFileName: String,
) {
    MACOS_ARM64(
        resourceDirectory = "darwin-arm64",
        libraryFileName = "libsouz_llama_bridge.dylib",
    ),
    MACOS_X64(
        resourceDirectory = "darwin-x64",
        libraryFileName = "libsouz_llama_bridge.dylib",
    ),
}

data class LocalHostInfo(
    val osName: String,
    val osArch: String,
    val totalRamBytes: Long,
    val totalRamGb: Int,
    val platform: LocalPlatform?,
)

class LocalHostInfoProvider {
    private val l = LoggerFactory.getLogger(LocalHostInfoProvider::class.java)

    fun current(): LocalHostInfo {
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()
        val platform = when {
            osName.contains("Mac", ignoreCase = true) && osArch.contains("aarch64", ignoreCase = true) ->
                LocalPlatform.MACOS_ARM64

            osName.contains("Mac", ignoreCase = true) && (
                osArch.contains("x86_64", ignoreCase = true) || osArch.contains("amd64", ignoreCase = true)
                ) -> LocalPlatform.MACOS_X64

            else -> null
        }

        val totalRamBytes = detectTotalRamBytes(osName)

        return LocalHostInfo(
            osName = osName,
            osArch = osArch,
            totalRamBytes = totalRamBytes,
            totalRamGb = (totalRamBytes / GIGABYTE).toInt(),
            platform = platform,
        )
    }

    private fun detectTotalRamBytes(osName: String): Long {
        val managementBytes = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
                ?.totalMemorySize
                ?: 0L
        }.getOrElse { error ->
            l.warn("Unable to read host memory via management APIs.", error)
            0L
        }
        if (managementBytes > 0L) {
            return managementBytes
        }

        if (!osName.contains("Mac", ignoreCase = true)) {
            return 0L
        }

        return runCatching {
            val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.memsize")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()
            check(exitCode == 0) { "sysctl exited with code $exitCode: $output" }
            output.toLong()
        }.getOrElse { error ->
            l.warn("Unable to read host memory via sysctl.", error)
            0L
        }
    }

    private companion object {
        const val GIGABYTE = 1024L * 1024L * 1024L
    }
}

object LocalModelProfiles {
    val QWEN3_4B_INSTRUCT_2507 = LocalModelProfile(
        gigaModel = LLMModel.LocalQwen3_4B_Instruct_2507,
        id = "local-qwen3-4b-instruct-2507",
        displayName = "Local Qwen3 4B Instruct 2507",
        huggingFaceRepoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        ggufFilename = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 8,
        defaultContextSize = 8192,
        maxContextSize = 8192,
        promptFamily = LocalPromptFamily.QWEN_CHATML,
        samplingDefaults = LocalSamplingDefaults(
            temperature = 0.2f,
            topP = 0.9f,
            topK = 40,
        ),
        licenseRequirements = LocalLicenseRequirements(
            summary = "Apache 2.0",
            requiresManualAcceptance = false,
        ),
    )

    val GEMMA4_E2B_IT = LocalModelProfile(
        gigaModel = LLMModel.LocalGemma4_E2B_It,
        id = "local-gemma-4-e2b-it",
        displayName = "Local Gemma 4 E2B Instruct",
        huggingFaceRepoId = "unsloth/gemma-4-E2B-it-GGUF",
        ggufFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 8,
        defaultContextSize = DEFAULT_MAX_TOKENS,
        maxContextSize = 96_000,
        promptFamily = LocalPromptFamily.GEMMA4,
        samplingDefaults = LocalSamplingDefaults(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 64,
        ),
        licenseRequirements = LocalLicenseRequirements(
            summary = "Apache 2.0",
            requiresManualAcceptance = false,
        ),
    )

    val GEMMA4_E4B_IT = LocalModelProfile(
        gigaModel = LLMModel.LocalGemma4_E4B_It,
        id = "local-gemma-4-e4b-it",
        displayName = "Local Gemma 4 E4B Instruct",
        huggingFaceRepoId = "unsloth/gemma-4-E4B-it-GGUF",
        ggufFilename = "gemma-4-E4B-it-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 16,
        defaultContextSize = DEFAULT_MAX_TOKENS,
        maxContextSize = 96_000,
        promptFamily = LocalPromptFamily.GEMMA4,
        samplingDefaults = LocalSamplingDefaults(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 64,
        ),
        licenseRequirements = LocalLicenseRequirements(
            summary = "Apache 2.0",
            requiresManualAcceptance = false,
        ),
    )

    val all: List<LocalModelProfile> = listOf(
        QWEN3_4B_INSTRUCT_2507,
        GEMMA4_E2B_IT,
        GEMMA4_E4B_IT,
    )

    fun forAlias(alias: String): LocalModelProfile? = all.firstOrNull { profile ->
        profile.id.equals(alias, ignoreCase = true) || profile.gigaModel.alias.equals(alias, ignoreCase = true)
    }

    fun availableForRam(totalRamGb: Int): List<LocalModelProfile> = all.filter { profile ->
        totalRamGb >= profile.minRamGb
    }.ifEmpty {
        error("Local inference requires at least ${QWEN3_4B_INSTRUCT_2507.minRamGb} GB RAM")
    }

    fun selectForRam(totalRamGb: Int): LocalModelProfile =
        availableForRam(totalRamGb).firstOrNull()
            ?: error("Local inference requires at least ${QWEN3_4B_INSTRUCT_2507.minRamGb} GB RAM")

    fun isLocalModelAlias(alias: String): Boolean = forAlias(alias) != null
}

object LocalEmbeddingProfiles {
    val EMBEDDING_GEMMA_300M = LocalEmbeddingProfile(
        embeddingsModel = EmbeddingsModel.LocalEmbeddingGemma300M,
        id = "local-embeddinggemma-300m",
        displayName = "Local EmbeddingGemma 300M",
        huggingFaceRepoId = "unsloth/embeddinggemma-300m-GGUF",
        ggufFilename = "embeddinggemma-300m-Q4_0.gguf",
        quantization = "Q4_0",
        minRamGb = 4,
        maxContextSize = 2048,
        outputDimensions = 768,
        queryPrefix = "task: search result | query: ",
        documentPrefix = "title: none | text: ",
        licenseRequirements = LocalLicenseRequirements(
            summary = "Gemma license",
            requiresManualAcceptance = false,
        ),
    )

    val all: List<LocalEmbeddingProfile> = listOf(EMBEDDING_GEMMA_300M)

    fun forAlias(alias: String): LocalEmbeddingProfile? = all.firstOrNull { profile ->
        profile.id.equals(alias, ignoreCase = true) || profile.embeddingsModel.alias.equals(alias, ignoreCase = true)
    }

    fun default(): LocalEmbeddingProfile = EMBEDDING_GEMMA_300M
}

object LocalModelBindings {
    fun linkedEmbeddingProfile(model: LLMModel): LocalEmbeddingProfile? = when (model.provider) {
        ru.souz.llms.LlmProvider.LOCAL -> LocalEmbeddingProfiles.default()
        else -> null
    }

    fun linkedEmbeddingProfile(profile: LocalModelProfile): LocalEmbeddingProfile =
        linkedEmbeddingProfile(profile.gigaModel) ?: LocalEmbeddingProfiles.default()

    fun requiredDownloadProfiles(profile: LocalModelProfile): List<LocalDownloadableProfile> =
        buildList {
            add(profile)
            add(linkedEmbeddingProfile(profile))
        }
}

data class LocalProviderStatus(
    val available: Boolean,
    val message: String,
    val selectedProfile: LocalModelProfile?,
    val availableModels: List<LLMModel>,
)

class LocalProviderAvailability(
    private val hostInfoProvider: LocalHostInfoProvider,
    private val modelStore: LocalModelStore,
    private val bridgeLoader: LocalBridgeLoader,
) : LocalModelAvailability {
    private val l = LoggerFactory.getLogger(LocalProviderAvailability::class.java)

    fun status(): LocalProviderStatus {
        val host = runCatching { hostInfoProvider.current() }
            .getOrElse { error ->
                l.warn("Local host detection failed.", error)
                return LocalProviderStatus(
                    available = false,
                    message = "Local inference is unavailable because host detection failed.",
                    selectedProfile = null,
                    availableModels = emptyList(),
                )
            }
        val platform = host.platform
            ?: return LocalProviderStatus(
                available = false,
                message = "Local inference is supported only on macOS arm64/x64.",
                selectedProfile = null,
                availableModels = emptyList(),
            )
        if (host.totalRamBytes <= 0L) {
            return LocalProviderStatus(
                available = false,
                message = "Local inference is unavailable because system memory could not be determined.",
                selectedProfile = null,
                availableModels = emptyList(),
            )
        }

        val selectedProfile = runCatching { LocalModelProfiles.selectForRam(host.totalRamGb) }
            .getOrElse { error ->
                return LocalProviderStatus(
                    available = false,
                    message = error.message ?: "Not enough RAM for local inference.",
                    selectedProfile = null,
                    availableModels = emptyList(),
                )
            }
        val eligibleProfiles = runCatching { LocalModelProfiles.availableForRam(host.totalRamGb) }
            .getOrElse { error ->
                return LocalProviderStatus(
                    available = false,
                    message = error.message ?: "Not enough RAM for local inference.",
                    selectedProfile = null,
                    availableModels = emptyList(),
                )
            }

        runCatching { bridgeLoader.healthcheck() }
            .onFailure { error ->
                l.warn("Local bridge healthcheck failed: {}", error.message)
                return LocalProviderStatus(
                    available = false,
                    message = "Local bridge is unavailable: ${error.message}",
                    selectedProfile = selectedProfile,
                    availableModels = emptyList(),
                )
            }

        val availableProfiles = eligibleProfiles.filter { profile ->
            modelStore.isPresent(profile) || modelStore.canDownload(profile)
        }
        if (availableProfiles.isEmpty()) {
            return LocalProviderStatus(
                available = false,
                message = "No supported local models are available and can be downloaded automatically.",
                selectedProfile = selectedProfile,
                availableModels = emptyList(),
            )
        }

        return LocalProviderStatus(
            available = true,
            message = "OK ($platform, ${host.totalRamGb} GB RAM)",
            selectedProfile = availableProfiles.firstOrNull { it.id == selectedProfile.id } ?: availableProfiles.first(),
            availableModels = availableProfiles.map { it.gigaModel },
        )
    }

    override fun availableGigaModels(): List<LLMModel> = status().availableModels

    override fun defaultGigaModel(): LLMModel? = status().selectedProfile?.gigaModel

    fun selectedProfile(): LocalModelProfile? = status().selectedProfile

    override fun isProviderAvailable(): Boolean = status().available
}
