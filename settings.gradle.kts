rootProject.name = "souz"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    resolutionStrategy {
        eachPlugin {
            val version = requested.version ?: return@eachPlugin
            when (requested.id.id) {
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.multiplatform" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$version")

                "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$version")

                "org.jetbrains.compose" ->
                    useModule("org.jetbrains.compose:compose-gradle-plugin:$version")

                "org.jetbrains.kotlinx.kover" ->
                    useModule("org.jetbrains.kotlinx:kover-gradle-plugin:$version")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        maven("https://mvn.mchv.eu/repository/mchv")
    }
}


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":agent")
include(":graph-engine")
include(":llms")
include(":native")
include(":ambientAgent")
include(":sharedLogic")
include(":sharedUI")
include(":skill-oauth-api")
include(":skill-oauth-impl")
include(":desktopApp")
include(":backend")
