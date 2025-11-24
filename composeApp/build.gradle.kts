import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
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

            implementation("org.jetbrains.compose.ui:ui-tooling-preview-desktop")
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

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
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.mockk)
        }
    }
}

compose.desktop {
    application {
        mainClass = "ru.abledo.MainKt"

        buildTypes {
            release {
                proguard {
                    isEnabled.set(false)
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ru.abledo"
            packageVersion = "1.0.0"

            modules("java.naming") // native build crash without it

            macOS {
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Needed for voice capture.</string>
                        <key>NSSystemAdministrationUsageDescription</key>
                        <string>Needed to observe input for shortcuts.</string>
                    """.trimIndent()
                }
            }

            // macOS dark mode support, works only on the release build, not in debug
            jvmArgs("-Dapple.awt.application.appearance=system")
        }
    }
}
