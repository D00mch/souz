import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Sync

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

val distributionPackageName = "Souz AI"
val distributionBundleId = "ru.souz"
val distributionDockName = "Souz AI"
val macSigningEnabled = providers.gradleProperty("mac.signing.enabled").orElse("false").map(String::toBoolean)
val macSigningIdentity = providers.gradleProperty("mac.signing.identity").orNull?.trim().orEmpty().ifBlank { null }
val macNotarizationEnabled = providers.gradleProperty("mac.notarization.enabled").orElse("false").map(String::toBoolean)
val macNotarizationAppleId = providers.gradleProperty("mac.notarization.appleId")
    .orElse(providers.environmentVariable("APPLE_ID"))
    .orNull
    ?.trim()
    .orEmpty()
    .ifBlank { null }
val macNotarizationPassword = providers.gradleProperty("mac.notarization.password")
    .orElse(providers.environmentVariable("APPLE_APP_SPECIFIC_PASSWORD"))
    .orNull
    ?.trim()
    .orEmpty()
    .ifBlank { null }
val macNotarizationTeamId = providers.gradleProperty("mac.notarization.teamId").orElse("A6VYB9APPM")

val sourceAppResourcesDir = layout.projectDirectory.dir("src/jvmMain/resources")
val preparedAppResourcesDir = layout.buildDirectory.dir("generated/souz-app-resources")

val prepareMacAppResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Prepare app resources and mirror TDLight/JNativeHook natives into common/darwin-* for packaging."

    from(sourceAppResourcesDir)
    into(preparedAppResourcesDir)

    // Compose copies app resources from <root>/common + OS/target subfolders.
    // Mirror native binaries from source-only darwin-* roots into common so they
    // are available in Contents/app/resources and loaded from java.library.path.
    from(sourceAppResourcesDir.file("darwin-arm64/libtdjni.macos_arm64.dylib")) {
        into("common/darwin-arm64")
    }
    from(sourceAppResourcesDir.file("darwin-x64/libtdjni.macos_amd64.dylib")) {
        into("common/darwin-x64")
    }
    from(sourceAppResourcesDir.file("darwin-arm64/libJNativeHook.dylib")) {
        into("common/darwin-arm64")
    }
    from(sourceAppResourcesDir.file("darwin-x64/libJNativeHook.dylib")) {
        into("common/darwin-x64")
    }
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
            implementation(libs.compose.ui.tooling.preview.desktop)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.sqlite.jdbc)

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
            implementation(libs.icu4j)
            implementation(libs.commons.csv)
            implementation(libs.bundles.letsPlot)
            implementation(libs.markdown)
            implementation(libs.jsoup)
            implementation(libs.java.diffUtils)

            // Excel support
            implementation(libs.poi)
            implementation(libs.poi.ooxml)

            // Telegram user client (TDLib)
            implementation(libs.tdlight.java)
            runtimeOnly("it.tdlight:tdlight-natives:${libs.versions.tdlight.natives.get()}:${tdlightNativeClassifier()}")
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit5)
            implementation(libs.junit.jupiterParams)
            implementation(libs.mockk)
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

        val isArm64 = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        val nativeResourceDir = if (isArm64) "darwin-arm64" else "darwin-x64"
        val nativeLibraryPath = if (includeAllMacNativeResources) {
            "\$APPDIR/resources/darwin-arm64:\$APPDIR/resources/darwin-x64"
        } else {
            "\$APPDIR/resources/$nativeResourceDir"
        }
        val sqliteLibraryPath = "\$APPDIR/resources"
        val sqliteLibraryName = "libsqlitejdbc.dylib"

        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = distributionPackageName
            packageVersion = "1.0.3"
            // Compose copies app resources from <root>/common + OS/target subfolders.
            appResourcesRootDir.set(preparedAppResourcesDir)

            // include HTTP client and java.sql needed by Apache Tika parser discovery
            modules("java.naming", "java.net.http", "java.sql")

            macOS {
                bundleID = distributionBundleId
                appCategory = "public.app-category.productivity"
                minimumSystemVersion = "12.0"
                appStore = isAppStoreRelease

                iconFile.set(File("src/jvmMain/resources/icon-light.icns"))

                signing {
                    sign.set(macSigningEnabled)
                    macSigningIdentity?.let { identity.set(it) } ?: identity.set("Souz AI")
                }

                notarization {
                    if (macNotarizationEnabled.get()) {
                        val appleId = requireNotNull(macNotarizationAppleId) {
                            "mac.notarization.appleId (or APPLE_ID env) is required when mac.notarization.enabled=true."
                        }
                        val appSpecificPassword = requireNotNull(macNotarizationPassword) {
                            "mac.notarization.password (or APPLE_APP_SPECIFIC_PASSWORD env) is required when mac.notarization.enabled=true."
                        }
                        appleID.set(appleId)
                        password.set(appSpecificPassword)
                        teamID.set(macNotarizationTeamId.get())
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
            // Include both architectures so universal bundles are not pinned to build-host arch.
            jvmArgs("-Djava.library.path=$nativeLibraryPath")
            // Force JNA to load the bundled dispatcher and never unpack jna*.tmp at runtime.
            jvmArgs("-Djna.boot.library.path=$nativeLibraryPath")
            jvmArgs("-Djna.nosys=true")
            jvmArgs("-Djna.noclasspath=true")
            // Force sqlite-jdbc to use bundled native binary and avoid sqlite-*.tmp extraction.
            jvmArgs("-Dorg.sqlite.lib.path=$sqliteLibraryPath")
            jvmArgs("-Dorg.sqlite.lib.name=$sqliteLibraryName")
            // Safety net: never let JNativeHook extract into Contents/app (which breaks code signature).
            jvmArgs("-Djnativehook.lib.path=/tmp/souz-jnativehook")
            jvmArgs("-Dapple.awt.application.appearance=system")
            // Needed for reflective access to AWT peers to attach NSVisualEffectView on macOS.
            jvmArgs("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
            jvmArgs("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
            jvmArgs("-Xdock:icon=src/jvmMain/resources/icon-light.icns")
            jvmArgs("-Xdock:name=$distributionDockName")
        }
    }
}

val releaseAppBundleDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$distributionPackageName.app")

val resignReleaseAppForNotarization by tasks.registering {
    group = "distribution"
    description = "Re-sign bundled native libraries in the release app before DMG packaging/notarization."
    dependsOn("createReleaseDistributable")

    onlyIf {
        macSigningEnabled.get() &&
            macSigningIdentity != null &&
            System.getProperty("os.name", "").lowercase().contains("mac")
    }

    doLast {
        val identity = requireNotNull(macSigningIdentity) {
            "mac.signing.identity is required when mac.signing.enabled=true."
        }

        val appBundle = releaseAppBundleDir.get().asFile
        check(appBundle.exists()) { "Release app bundle not found: $appBundle" }

        val appEntitlementsFile = if (isAppStoreRelease) {
            project.file("src/jvmMain/resources/entitlements.plist")
        } else {
            project.file("src/jvmMain/resources/entitlements-dev.plist")
        }
        val runtimeEntitlementsFile = if (isAppStoreRelease) {
            project.file("src/jvmMain/resources/runtime-entitlements.plist")
        } else {
            project.file("src/jvmMain/resources/runtime-entitlements-dev.plist")
        }

        fun runCodesign(vararg args: String) {
            exec {
                commandLine("codesign", *args)
            }
        }

        val nativeResourceDir = appBundle.resolve("Contents/app/resources")
        check(nativeResourceDir.exists()) { "Native resource directory not found: $nativeResourceDir" }

        val nativeResourceLibs = fileTree(nativeResourceDir) {
            include("**/*.dylib", "**/*.jnilib")
        }.files.sortedBy { it.absolutePath }
        check(nativeResourceLibs.isNotEmpty()) {
            "No native resource libraries were found under $nativeResourceDir."
        }

        logger.lifecycle("Re-signing ${nativeResourceLibs.size} native resource libraries in ${nativeResourceDir.absolutePath}")
        nativeResourceLibs.forEach { nativeLib ->
            runCodesign(
                "--force",
                "--timestamp",
                "--options",
                "runtime",
                "--sign",
                identity,
                nativeLib.absolutePath
            )
        }

        val runtimeBundle = appBundle.resolve("Contents/runtime")
        if (runtimeBundle.exists()) {
            runCodesign(
                "--force",
                "--timestamp",
                "--options",
                "runtime",
                "--entitlements",
                runtimeEntitlementsFile.absolutePath,
                "--sign",
                identity,
                runtimeBundle.absolutePath
            )
        }

        val launcherBinary = appBundle.resolve("Contents/MacOS/${appBundle.nameWithoutExtension}")
        if (launcherBinary.exists()) {
            runCodesign(
                "--force",
                "--timestamp",
                "--options",
                "runtime",
                "--entitlements",
                appEntitlementsFile.absolutePath,
                "--sign",
                identity,
                launcherBinary.absolutePath
            )
        }

            runCodesign(
                "--force",
                "--timestamp",
                "--options",
                "runtime",
                "--entitlements",
                appEntitlementsFile.absolutePath,
                "--sign",
                identity,
                appBundle.absolutePath
            )

        exec {
            commandLine("codesign", "--verify", "--deep", "--strict", appBundle.absolutePath)
        }
    }
}

tasks.matching { it.name == "packageReleaseDmg" || it.name == "notarizeReleaseDmg" }.configureEach {
    dependsOn(resignReleaseAppForNotarization)
}

tasks.matching { it.name == "prepareAppResources" || it.name == "createReleaseDistributable" || it.name == "notarizeReleaseDmg" }.configureEach {
    dependsOn(prepareMacAppResources)
}
