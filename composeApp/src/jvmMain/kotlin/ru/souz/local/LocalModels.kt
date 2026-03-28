package ru.souz.local

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import org.slf4j.LoggerFactory
import ru.souz.giga.GigaModel

enum class LocalTemplateFamily {
    QWEN3,
    LLAMA_3_1,
    GIGACHAT_3_1,
}

data class LocalLicenseRequirements(
    val summary: String,
    val requiresManualAcceptance: Boolean = false,
)

data class LocalModelProfile(
    val gigaModel: GigaModel,
    val id: String,
    val displayName: String,
    val huggingFaceRepoId: String,
    val ggufFilename: String,
    val quantization: String,
    val minRamGb: Int,
    val templateFamily: LocalTemplateFamily,
    val defaultContextSize: Int,
    val maxContextSize: Int = defaultContextSize,
    val useNativeGrammar: Boolean = false,
    val licenseRequirements: LocalLicenseRequirements,
    val defaultGpuLayers: Int = 99,
) {
    val downloadUrl: String =
        "https://huggingface.co/$huggingFaceRepoId/resolve/main/$ggufFilename?download=true"
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

        val totalRamBytes = (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
            ?.totalMemorySize
            ?: 0L

        return LocalHostInfo(
            osName = osName,
            osArch = osArch,
            totalRamBytes = totalRamBytes,
            totalRamGb = (totalRamBytes / GIGABYTE).toInt(),
            platform = platform,
        )
    }

    private companion object {
        const val GIGABYTE = 1024L * 1024L * 1024L
    }
}

object LocalModelProfiles {
    val QWEN3_4B_INSTRUCT_2507 = LocalModelProfile(
        gigaModel = GigaModel.LocalQwen3_4B_Instruct_2507,
        id = "local-qwen3-4b-instruct-2507",
        displayName = "Local Qwen3 4B Instruct 2507",
        huggingFaceRepoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        ggufFilename = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 8,
        templateFamily = LocalTemplateFamily.QWEN3,
        defaultContextSize = 8192,
        maxContextSize = 8192,
        licenseRequirements = LocalLicenseRequirements(
            summary = "Apache 2.0",
            requiresManualAcceptance = false,
        ),
    )

    val LLAMA_3_1_8B_INSTRUCT = LocalModelProfile(
        gigaModel = GigaModel.LocalLlama_3_1_8B_Instruct,
        id = "local-llama-3.1-8b-instruct",
        displayName = "Local Llama 3.1 8B Instruct",
        huggingFaceRepoId = "unsloth/Llama-3.1-8B-Instruct-GGUF",
        ggufFilename = "Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 16,
        templateFamily = LocalTemplateFamily.LLAMA_3_1,
        defaultContextSize = 8192,
        maxContextSize = 16384,
        licenseRequirements = LocalLicenseRequirements(
            summary = "Llama 3.1 community license",
            requiresManualAcceptance = true,
        ),
    )

    val GIGACHAT_3_1_10B_A1_8B = LocalModelProfile(
        gigaModel = GigaModel.LocalGigaChat_3_1_10B_A1_8B,
        id = "local-gigachat-3.1-10b-a1.8b",
        displayName = "Local GigaChat 3.1 10B A1.8B",
        huggingFaceRepoId = "ai-sage/GigaChat3.1-10B-A1.8B-GGUF",
        ggufFilename = "GigaChat3.1-10B-A1.8B-q4_K_M.gguf",
        quantization = "Q4_K_M",
        minRamGb = 16,
        templateFamily = LocalTemplateFamily.GIGACHAT_3_1,
        defaultContextSize = 8192,
        maxContextSize = 16384,
        licenseRequirements = LocalLicenseRequirements(
            summary = "MIT",
            requiresManualAcceptance = false,
        ),
    )

    val all: List<LocalModelProfile> = listOf(
        QWEN3_4B_INSTRUCT_2507,
        LLAMA_3_1_8B_INSTRUCT,
        GIGACHAT_3_1_10B_A1_8B,
    )

    fun forAlias(alias: String): LocalModelProfile? = all.firstOrNull { profile ->
        profile.id.equals(alias, ignoreCase = true) || profile.gigaModel.alias.equals(alias, ignoreCase = true)
    }

    fun availableForRam(totalRamGb: Int): List<LocalModelProfile> = all.filter { profile ->
        totalRamGb >= profile.minRamGb
    }.ifEmpty {
        error("Local inference requires at least ${QWEN3_4B_INSTRUCT_2507.minRamGb} GB RAM")
    }

    fun selectForRam(totalRamGb: Int): LocalModelProfile = when {
        totalRamGb >= LLAMA_3_1_8B_INSTRUCT.minRamGb -> LLAMA_3_1_8B_INSTRUCT
        totalRamGb >= QWEN3_4B_INSTRUCT_2507.minRamGb -> QWEN3_4B_INSTRUCT_2507
        else -> error("Local inference requires at least ${QWEN3_4B_INSTRUCT_2507.minRamGb} GB RAM")
    }

    fun isLocalModelAlias(alias: String): Boolean = forAlias(alias) != null
}

data class LocalProviderStatus(
    val available: Boolean,
    val message: String,
    val selectedProfile: LocalModelProfile?,
    val availableModels: List<GigaModel>,
)

class LocalProviderAvailability(
    private val hostInfoProvider: LocalHostInfoProvider,
    private val modelStore: LocalModelStore,
    private val bridgeLoader: LocalBridgeLoader,
) {
    private val l = LoggerFactory.getLogger(LocalProviderAvailability::class.java)

    fun status(): LocalProviderStatus {
        val host = hostInfoProvider.current()
        val platform = host.platform
            ?: return LocalProviderStatus(
                available = false,
                message = "Local inference is supported only on macOS arm64/x64.",
                selectedProfile = null,
                availableModels = emptyList(),
            )

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

    fun availableGigaModels(): List<GigaModel> = status().availableModels

    fun defaultGigaModel(): GigaModel? = status().selectedProfile?.gigaModel

    fun selectedProfile(): LocalModelProfile? = status().selectedProfile

    fun isProviderAvailable(): Boolean = status().available
}
