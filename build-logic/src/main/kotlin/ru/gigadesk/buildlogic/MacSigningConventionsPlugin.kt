package ru.gigadesk.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

data class MacNotarizationCredentials(
    val appleId: String,
    val password: String,
    val teamId: String,
)

class MacSigningSettings internal constructor(
    val signingEnabled: Provider<Boolean>,
    val signingIdentity: String?,
    val notarizationEnabled: Provider<Boolean>,
    val notarizationAppleId: String?,
    val notarizationPassword: String?,
    val notarizationTeamId: Provider<String>,
) {
    fun notarizationCredentialsOrNull(): MacNotarizationCredentials? {
        if (!notarizationEnabled.get()) {
            return null
        }

        val appleId = notarizationAppleId
        val password = notarizationPassword

        require(!appleId.isNullOrBlank()) {
            "mac.notarization.appleId (or APPLE_ID env) is required when mac.notarization.enabled=true."
        }
        require(!password.isNullOrBlank()) {
            "mac.notarization.password (or APPLE_APP_SPECIFIC_PASSWORD env) is required when mac.notarization.enabled=true."
        }

        return MacNotarizationCredentials(
            appleId = appleId,
            password = password,
            teamId = notarizationTeamId.get(),
        )
    }
}

class MacSigningConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.add("macSigning", project.providers.macSigningSettings())
    }
}

private fun ProviderFactory.macSigningSettings(defaultTeamId: String = "A6VYB9APPM"): MacSigningSettings {
    return MacSigningSettings(
        signingEnabled = gradleProperty("mac.signing.enabled").orElse("false").map(String::toBoolean),
        signingIdentity = gradleProperty("mac.signing.identity").orNull?.trim().orEmpty().ifBlank { null },
        notarizationEnabled = gradleProperty("mac.notarization.enabled").orElse("false").map(String::toBoolean),
        notarizationAppleId = gradleProperty("mac.notarization.appleId")
            .orElse(environmentVariable("APPLE_ID"))
            .orNull
            ?.trim()
            .orEmpty()
            .ifBlank { null },
        notarizationPassword = gradleProperty("mac.notarization.password")
            .orElse(environmentVariable("APPLE_APP_SPECIFIC_PASSWORD"))
            .orNull
            ?.trim()
            .orEmpty()
            .ifBlank { null },
        notarizationTeamId = gradleProperty("mac.notarization.teamId").orElse(defaultTeamId),
    )
}
