rootProject.name = "souz"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":agent")
include(":graph-engine")
include(":llms")
include(":sharedLogic")
include(":skill-oauth-api")
include(":skill-plugin-api")
include(":skill-oauth-impl")
include(":job-api")
include(":job-impl")
include(":backend")
