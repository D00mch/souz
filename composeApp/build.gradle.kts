import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import ru.souz.buildlogic.ComposeAppConventionsExtension
import ru.souz.buildlogic.MacSigningSettings

fun tdlightNativeClassifier(): String {
    val osName = System.getProperty("os.name", "").lowercase()
    val osArch = System.getProperty("os.arch", "").lowercase()

    return when {
        osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            "macos_arm64"
        } else {
            "macos_amd64"
        }

        osName.contains("win") -> "windows_amd64"
        osName.contains("linux") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            "linux_arm64_gnu_ssl3"
        } else {
            "linux_amd64_gnu_ssl3"
        }

        else -> error("Unsupported OS for tdlight natives: $osName ($osArch)")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    id("ru.souz.compose-app-conventions")
}

val explicitEdition = providers.gradleProperty("edition").orNull?.trim()?.lowercase().orEmpty().ifBlank { null }
if (explicitEdition != null) {
    require(explicitEdition in setOf("ru", "en")) {
        "Unsupported edition '$explicitEdition'. Use -Pedition=ru or -Pedition=en."
    }
}

val requestedTasks = gradle.startParameter.taskNames
val ruTaskRequested = requestedTasks.any { it.endsWith("packageRuReleaseDmg") }
val enTaskRequested = requestedTasks.any { it.endsWith("packageEnReleaseDmg") }

val inferredEdition = when {
    ruTaskRequested && enTaskRequested -> {
        error("Cannot invoke packageRuReleaseDmg and packageEnReleaseDmg in a single Gradle run.")
    }

    ruTaskRequested -> "ru"
    enTaskRequested -> "en"
    else -> null
}

if (explicitEdition != null && inferredEdition != null && explicitEdition != inferredEdition) {
    error("Conflicting edition: -Pedition=$explicitEdition but requested task implies $inferredEdition.")
}

val edition = explicitEdition ?: inferredEdition ?: "ru"

val editionPackageName = if (edition == "ru") "Союз ИИ" else "Souz AI"
val editionBundleId = if (edition == "ru") "ru.souz" else "en.souz"
val editionDockName = if (edition == "ru") "Союз c ИИ" else "Souz AI"

extensions.getByType<ComposeAppConventionsExtension>().edition.set(edition)
val macSigning = extensions.getByType<MacSigningSettings>()

kotlin {
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()

                systemProperty("junit.jupiter.execution.timeout.default", "5 m")
                systemProperty("junit.jupiter.execution.timeout.mode", "enabled")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.animation)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)

            implementation(libs.kodein.di.framework.compose)

            // ui helpers
            implementation(libs.platformtools.darkmodedetector)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // coroutines
            implementation(libs.kotlinx.coroutines)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmMain.dependencies {
            implementation(libs.compose.ui.tooling.preview.desktop)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // proto
            implementation(projects.proto)
            implementation(libs.grpc.nettyShaded)

            // kotlin
            implementation(kotlin("reflect"))

            // json & logging
            implementation(libs.jackson)
            implementation(libs.logback)
            implementation(libs.slfj)
            implementation(libs.log4j.to.slf4j)

            // ktor client (core + cio + logging + auth + content-negotiation + serialization)
            implementation(libs.bundles.ktorClient)
            implementation(libs.ktor.serializationJackson)

            // desktop manipulation
            implementation(libs.jnativehook)
            implementation(libs.jna)
            implementation(libs.jna.platform)

            // search index
            implementation(libs.lucene.core)
            implementation(libs.tika.core)
            implementation(libs.tika.parsersStandardPackage)
            implementation(libs.commons.csv)
            implementation(libs.bundles.letsPlot)
            implementation(libs.markdown)
            implementation(libs.java.diffUtils)

            // ktor server (local API for mobile companion)
            implementation(libs.bundles.ktorServer)

            // Excel support
            implementation(libs.poi)
            implementation(libs.poi.ooxml)

            // Telegram user client (TDLib)
            implementation(libs.tdlight.java)
            implementation("org.xerial:sqlite-jdbc:3.45.1.0")
            runtimeOnly("it.tdlight:tdlight-natives:${libs.versions.tdlight.natives.get()}:${tdlightNativeClassifier()}")
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit5)
            implementation(libs.junit.jupiterParams)
            implementation(libs.mockk)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

val isAppStoreRelease: Boolean = (project.findProperty("macOsAppStoreRelease") as String?)?.toBoolean() ?: false
val macBuildNumber: String = (project.findProperty("buildNumber") as String?) ?: "1"
val includeAllMacNativeResources: Boolean =
    (project.findProperty("mac.includeAllNativeResources") as String?)?.toBoolean() ?: false

compose.desktop {
    application {
        mainClass = "ru.souz.MainKt"
        jvmArgs("-Dsouz.edition=$edition")

        val isArm64 = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        val nativeResourceDir = if (isArm64) "darwin-arm64" else "darwin-x64"
        val nativeLibraryPath = if (includeAllMacNativeResources) {
            "\$APPDIR/resources/darwin-arm64:\$APPDIR/resources/darwin-x64"
        } else {
            "\$APPDIR/resources/$nativeResourceDir"
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = editionPackageName
            packageVersion = "1.0.0"

            modules("java.naming", "java.net.http") // include HTTP client used by Telegram bootstrap

            macOS {
                bundleID = editionBundleId
                appCategory = "public.app-category.productivity"
                minimumSystemVersion = "12.0"
                appStore = isAppStoreRelease

                iconFile.set(File("src/jvmMain/resources/icon-light.icns"))

                signing {
                    sign.set(macSigning.signingEnabled)
                    macSigning.signingIdentity?.let { identity.set(it) } ?: identity.set("Souz AI")
                }

                notarization {
                    macSigning.notarizationCredentialsOrNull()?.let { credentials ->
                        appleID.set(credentials.appleId)
                        password.set(credentials.password)
                        teamID.set(credentials.teamId)
                    }
                }

                infoPlist {
                    packageBuildVersion = macBuildNumber
                    extraKeysRawXml = """
                        <key>ITSAppUsesNonExemptEncryption</key><false/>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Needed for voice capture.</string>
                        <key>NSSystemAdministrationUsageDescription</key>
                        <string>Needed to observe input for shortcuts.</string>
                        <key>NSAppleEventsUsageDescription</key>
                        <string>Needed to control Chrome for browser automation.</string>
                    """.trimIndent()
                }

                if (isAppStoreRelease) {
                    entitlementsFile.set(project.file("src/jvmMain/resources/entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("src/jvmMain/resources/runtime-entitlements.plist"))
                    provisioningProfile.set(project.file("src/jvmMain/resources/embedded.provisionprofile"))
                    runtimeProvisioningProfile.set(project.file("src/jvmMain/resources/runtime.provisionprofile"))
                } else {
                    entitlementsFile.set(project.file("src/jvmMain/resources/entitlements-dev.plist"))
                    runtimeEntitlementsFile.set(project.file("src/jvmMain/resources/runtime-entitlements-dev.plist"))
                }
            }

            // macOS dark mode support, works only on the release build, not in debug
            jvmArgs("-Dsouz.edition=$edition")
            // Include both architectures so universal bundles are not pinned to build-host arch.
            jvmArgs("-Djava.library.path=$nativeLibraryPath")
            // Safety net: never let JNativeHook extract into Contents/app (which breaks code signature).
            jvmArgs("-Djnativehook.lib.path=/tmp/souz-jnativehook")
            jvmArgs("-Dapple.awt.application.appearance=system")
            jvmArgs("-Xdock:icon=src/jvmMain/resources/icon-light.icns")
            jvmArgs("-Xdock:name=$editionDockName")
        }
    }
}
