package ru.gigadesk.buildlogic

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.process.JavaForkOptions
import javax.inject.Inject

abstract class ComposeAppConventionsExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val edition: Property<String> = objects.property(String::class.java).convention("ru")
}

class ComposeAppConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("ru.gigadesk.mac-signing-conventions")
        val extension = project.extensions.create(
            "composeAppConventions",
            ComposeAppConventionsExtension::class.java,
        )

        configureNativeExtraction(project)
        configureEditionAwareTasks(project, extension)
    }
}

private fun configureNativeExtraction(project: Project) {
    val tdlightMacosArm64NativeFileName = "libtdjni.macos_arm64.dylib"
    val tdlightMacosArm64NativeTargetDir = project.layout.projectDirectory.dir("src/jvmMain/resources/darwin-arm64")
    val tdlightMacosArm64NativeConfiguration = project.configurations.create("tdlightMacosArm64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "TDLight macOS arm64 native jar used to extract local JNI binary"
    }

    val jnativehookMacosArm64NativeFileName = "libJNativeHook.dylib"
    val jnativehookMacosArm64NativeTargetDir = project.layout.projectDirectory.dir("src/jvmMain/resources/darwin-arm64")
    val jnativehookMacosArm64NativeConfiguration = project.configurations.create("jnativehookMacosArm64Native") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "JNativeHook jar used to extract macOS arm64 JNI binary"
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

    val syncTdlightNativeMacosArm64 = project.tasks.register("syncTdlightNativeMacosArm64", Copy::class.java) {
        group = "tdlight"
        description = "Extract TDLight macOS arm64 JNI binary into src/jvmMain/resources/darwin-arm64"
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

    val syncJnativehookNativeMacosArm64 = project.tasks.register("syncJnativehookNativeMacosArm64", Copy::class.java) {
        group = "native"
        description = "Extract JNativeHook macOS arm64 JNI binary into src/jvmMain/resources/darwin-arm64"
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

    project.tasks.configureEach {
        if (name == "jvmProcessResources" || name == "processJvmMainResources") {
            dependsOn(syncTdlightNativeMacosArm64)
            dependsOn(syncJnativehookNativeMacosArm64)
        }
    }

    project.tasks.configureEach {
        if (name == "prepareAppResources" && this is Sync) {
            dependsOn(syncTdlightNativeMacosArm64)
            dependsOn(syncJnativehookNativeMacosArm64)
            from(tdlightMacosArm64NativeTargetDir) {
                include(tdlightMacosArm64NativeFileName)
                into("darwin-arm64")
            }
            from(jnativehookMacosArm64NativeTargetDir) {
                include(jnativehookMacosArm64NativeFileName)
                into("darwin-arm64")
            }
        }
    }
}

private fun configureEditionAwareTasks(
    project: Project,
    extension: ComposeAppConventionsExtension,
) {
    project.tasks.matching { it.name == "jvmRun" }.configureEach {
        if (this is JavaForkOptions) {
            val javaForkTask = this
            doFirst {
                javaForkTask.systemProperty("gigadesk.edition", extension.edition.get())
            }
        }
    }

    project.tasks.withType(Test::class.java).configureEach {
        doFirst {
            systemProperty("gigadesk.edition", extension.edition.get())
        }
    }

    project.tasks.register("packageRuReleaseDmg") {
        group = "distribution"
        description = "Build RU release DMG."
        dependsOn("packageReleaseDmg")
    }

    project.tasks.register("packageEnReleaseDmg") {
        group = "distribution"
        description = "Build EN release DMG."
        dependsOn("packageReleaseDmg")
    }
}
