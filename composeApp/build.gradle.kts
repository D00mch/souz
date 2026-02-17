import org.gradle.api.tasks.testing.Test
import org.gradle.process.JavaForkOptions
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
val editionBundleId = if (edition == "ru") "ru.gigadesk" else "en.gigadesk"
val editionDockName = if (edition == "ru") "Союз c ИИ" else "Souz AI"

tasks.matching { it.name == "jvmRun" }.configureEach {
    if (this is JavaForkOptions) {
        systemProperty("gigadesk.edition", edition)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("gigadesk.edition", edition)
}

tasks.register("packageRuReleaseDmg") {
    group = "distribution"
    description = "Build RU release DMG."
    dependsOn("packageReleaseDmg")
}

tasks.register("packageEnReleaseDmg") {
    group = "distribution"
    description = "Build EN release DMG."
    dependsOn("packageReleaseDmg")
}

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
            implementation(files("src/jvmMain/resources/darwin-arm64"))

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

            // ktor client (core + cio + logging + auth + content-negotiation + serialization)
            implementation(libs.bundles.ktorClient)
            implementation(libs.ktor.serializationJackson)

            // desktop manipulation
            implementation(libs.jnativehook)
            implementation(libs.jna)
            implementation(libs.jna.platform)

            // audio
            implementation(libs.jave.core)
            implementation(libs.jave.nativebinOsxm1)

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
            implementation("it.tdlight:tdlight-natives:${libs.versions.tdlight.natives.get()}:${tdlightNativeClassifier()}")
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

compose.desktop {
    application {
        mainClass = "ru.gigadesk.MainKt"
        jvmArgs("-Dgigadesk.edition=$edition")

        buildTypes {
            release {
                proguard {
                    isEnabled.set(false)
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = editionPackageName
            packageVersion = "1.0.0"

            modules("java.naming") // native build crash without it

            macOS {
                bundleID = editionBundleId
                iconFile.set(File("src/jvmMain/resources/icon-light.icns"))

                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Needed for voice capture.</string>
                        <key>NSSystemAdministrationUsageDescription</key>
                        <string>Needed to observe input for shortcuts.</string>
                        <key>NSAppleEventsUsageDescription</key>
                        <string>Needed to control Chrome for browser automation.</string>
                    """.trimIndent()
                }
            }

            // macOS dark mode support, works only on the release build, not in debug
            jvmArgs("-Dgigadesk.edition=$edition")
            jvmArgs("-Dapple.awt.application.appearance=system")
            jvmArgs("-Xdock:icon=src/jvmMain/resources/icon-light.icns")
            jvmArgs("-Xdock:name=$editionDockName")
        }
    }
}
