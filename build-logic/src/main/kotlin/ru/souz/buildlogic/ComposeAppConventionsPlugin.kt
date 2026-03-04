package ru.souz.buildlogic

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import java.io.File

class ComposeAppConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("ru.souz.mac-signing-conventions")
        configureNativeExtraction(project)
        configureDistributionTasks(project)
    }
}

private fun configureNativeExtraction(project: Project) {
    val includeAllMacNativeResources = project.providers
        .gradleProperty("mac.includeAllNativeResources")
        .orElse("false")
        .map(String::toBoolean)
        .get()
    val isArm64Build = System.getProperty("os.arch", "")
        .lowercase()
        .let { it.contains("aarch64") || it.contains("arm64") }
    val generatedNativeResourcesRoot = project.layout.buildDirectory.dir("generated/native-resources")
    val tdlightMacosArm64NativeFileName = "libtdjni.macos_arm64.dylib"
    val tdlightMacosArm64NativeTargetDir = generatedNativeResourcesRoot.map { it.dir("darwin-arm64") }
    val tdlightMacosArm64NativeConfiguration = project.configurations.create("tdlightMacosArm64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "TDLight macOS arm64 native jar used to extract local JNI binary"
    }

    val tdlightMacosX64NativeFileName = "libtdjni.macos_amd64.dylib"
    val tdlightMacosX64NativeTargetDir = generatedNativeResourcesRoot.map { it.dir("darwin-x64") }
    val tdlightMacosX64NativeConfiguration = project.configurations.create("tdlightMacosX64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "TDLight macOS x64 native jar used to extract local JNI binary"
    }

    val jnativehookMacosArm64NativeFileName = "libJNativeHook.dylib"
    val jnativehookMacosArm64NativeTargetDir = generatedNativeResourcesRoot.map { it.dir("darwin-arm64") }
    val jnativehookMacosArm64NativeConfiguration = project.configurations.create("jnativehookMacosArm64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "JNativeHook jar used to extract macOS arm64 JNI binary"
    }

    val jnativehookMacosX64NativeFileName = "libJNativeHook.dylib"
    val jnativehookMacosX64NativeTargetDir = generatedNativeResourcesRoot.map { it.dir("darwin-x64") }
    val jnativehookMacosX64NativeConfiguration = project.configurations.create("jnativehookMacosX64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "JNativeHook jar used to extract macOS x64 JNI binary"
    }

    val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val tdlightNativesVersion = libs.findVersion("tdlight-natives").orElseThrow {
        GradleException("Version 'tdlight-natives' is missing in libs.versions.toml")
    }.requiredVersion
    val jnativehookVersion = libs.findVersion("jnativehook").orElseThrow {
        GradleException("Version 'jnativehook' is missing in libs.versions.toml")
    }.requiredVersion

    project.dependencies.add(
        tdlightMacosArm64NativeConfiguration.name,
        "it.tdlight:tdlight-natives:$tdlightNativesVersion:macos_arm64@jar",
    )
    project.dependencies.add(
        jnativehookMacosArm64NativeConfiguration.name,
        "com.github.kwhat:jnativehook:$jnativehookVersion@jar",
    )
    project.dependencies.add(
        tdlightMacosX64NativeConfiguration.name,
        "it.tdlight:tdlight-natives:$tdlightNativesVersion:macos_amd64@jar",
    )
    project.dependencies.add(
        jnativehookMacosX64NativeConfiguration.name,
        "com.github.kwhat:jnativehook:$jnativehookVersion@jar",
    )

    val syncTdlightNativeMacosArm64 = project.tasks.register("syncTdlightNativeMacosArm64", Copy::class.java) {
        group = "tdlight"
        description = "Extract TDLight macOS arm64 JNI binary into build/generated/native-resources/darwin-arm64"
        from(
            { tdlightMacosArm64NativeConfiguration.files.map { project.zipTree(it) } },
            Action {
                include("META-INF/tdlightjni/$tdlightMacosArm64NativeFileName")
                eachFile { path = tdlightMacosArm64NativeFileName }
                includeEmptyDirs = false
            },
        )
        into(tdlightMacosArm64NativeTargetDir)
    }

    val syncTdlightNativeMacosX64 = project.tasks.register("syncTdlightNativeMacosX64", Copy::class.java) {
        group = "tdlight"
        description = "Extract TDLight macOS x64 JNI binary into build/generated/native-resources/darwin-x64"
        from(
            { tdlightMacosX64NativeConfiguration.files.map { project.zipTree(it) } },
            Action {
                include("META-INF/tdlightjni/$tdlightMacosX64NativeFileName")
                eachFile { path = tdlightMacosX64NativeFileName }
                includeEmptyDirs = false
            },
        )
        into(tdlightMacosX64NativeTargetDir)
    }

    val syncJnativehookNativeMacosArm64 = project.tasks.register("syncJnativehookNativeMacosArm64", Copy::class.java) {
        group = "native"
        description = "Extract JNativeHook macOS arm64 JNI binary into build/generated/native-resources/darwin-arm64"
        from(
            { jnativehookMacosArm64NativeConfiguration.files.map { project.zipTree(it) } },
            Action {
                include("com/github/kwhat/jnativehook/lib/darwin/arm64/$jnativehookMacosArm64NativeFileName")
                eachFile { path = jnativehookMacosArm64NativeFileName }
                includeEmptyDirs = false
            },
        )
        into(jnativehookMacosArm64NativeTargetDir)
    }

    val syncJnativehookNativeMacosX64 = project.tasks.register("syncJnativehookNativeMacosX64", Copy::class.java) {
        group = "native"
        description = "Extract JNativeHook macOS x64 JNI binary into build/generated/native-resources/darwin-x64"
        from(
            { jnativehookMacosX64NativeConfiguration.files.map { project.zipTree(it) } },
            Action {
                include("com/github/kwhat/jnativehook/lib/darwin/x86_64/$jnativehookMacosX64NativeFileName")
                eachFile { path = jnativehookMacosX64NativeFileName }
                includeEmptyDirs = false
            },
        )
        into(jnativehookMacosX64NativeTargetDir)
    }

    val selectedSyncTasks = buildList {
        if (includeAllMacNativeResources || isArm64Build) {
            add(syncTdlightNativeMacosArm64)
            add(syncJnativehookNativeMacosArm64)
        }
        if (includeAllMacNativeResources || !isArm64Build) {
            add(syncTdlightNativeMacosX64)
            add(syncJnativehookNativeMacosX64)
        }
    }

    project.tasks.configureEach {
        if (name == "jvmProcessResources" || name == "processJvmMainResources") {
            selectedSyncTasks.forEach(::dependsOn)
        }
    }

    project.tasks.configureEach {
        if (name == "prepareAppResources" && this is Sync) {
            selectedSyncTasks.forEach(::dependsOn)
            if (includeAllMacNativeResources || isArm64Build) {
                from(tdlightMacosArm64NativeTargetDir) {
                    include(tdlightMacosArm64NativeFileName)
                    into("darwin-arm64")
                }
                from(jnativehookMacosArm64NativeTargetDir) {
                    include(jnativehookMacosArm64NativeFileName)
                    into("darwin-arm64")
                }
            }
            if (includeAllMacNativeResources || !isArm64Build) {
                from(tdlightMacosX64NativeTargetDir) {
                    include(tdlightMacosX64NativeFileName)
                    into("darwin-x64")
                }
                from(jnativehookMacosX64NativeTargetDir) {
                    include(jnativehookMacosX64NativeFileName)
                    into("darwin-x64")
                }
            }
        }
    }
}

private fun configureDistributionTasks(project: Project) {
    val macSigning = project.extensions.getByType(MacSigningSettings::class.java)

    val patchReleaseAppForNotarization = project.tasks.register("patchReleaseAppForNotarization") {
        group = "distribution"
        description = "Re-sign release app bundle before packaging/notarization."
        dependsOn("createReleaseDistributable")
        outputs.upToDateWhen { false }

        doLast {
            if (!System.getProperty("os.name", "").lowercase().contains("mac")) {
                logger.info("Skipping release app re-sign: current OS is not macOS.")
                return@doLast
            }
            if (!macSigning.signingEnabled.get()) {
                logger.info("Skipping release app re-sign: mac.signing.enabled=false.")
                return@doLast
            }

            val signingIdentity = macSigning.signingIdentity
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("mac.signing.identity is required for release app re-sign.")

            val releaseAppDir = project.layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
            val appBundles = releaseAppDir.listFiles { file -> file.isDirectory && file.name.endsWith(".app") }?.toList().orEmpty()
            if (appBundles.isEmpty()) {
                logger.warn("No .app bundles found in {}", releaseAppDir.absolutePath)
                return@doLast
            }

            appBundles.forEach { appBundle ->
                resignAppBundleAndVerify(project, appBundle, signingIdentity)
            }
        }
    }

    project.tasks.configureEach {
        if (name == "packageReleaseDmg" || name == "notarizeReleaseDmg" || name == "packageReleasePkg" || name == "notarizeReleasePkg") {
            dependsOn(patchReleaseAppForNotarization)
        }
    }
}

private fun resignAppBundleAndVerify(
    project: Project,
    appBundle: File,
    signingIdentity: String,
) {
    runCommand(
        project = project,
        command = listOf(
            "/usr/bin/codesign",
            "--force",
            "--deep",
            "--options",
            "runtime",
            "--timestamp",
            "--preserve-metadata=entitlements,requirements,flags",
            "--sign",
            signingIdentity,
            appBundle.absolutePath,
        ),
        context = "Re-signing app bundle: ${appBundle.name}",
    )

    runCommand(
        project = project,
        command = listOf(
            "/usr/bin/codesign",
            "--verify",
            "--deep",
            "--strict",
            "--verbose=2",
            appBundle.absolutePath,
        ),
        context = "Verifying re-signed app bundle: ${appBundle.name}",
    )
}

private fun runCommand(
    project: Project,
    command: List<String>,
    context: String,
) {
    val process = ProcessBuilder(command)
        .directory(project.projectDir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val details = if (output.isBlank()) "<no output>" else output
        throw GradleException("$context failed (exit $exitCode): ${command.joinToString(" ")}\n$details")
    }
    if (output.isNotBlank()) {
        project.logger.info(output)
    }
}
