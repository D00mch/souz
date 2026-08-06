rootProject.name = "souz"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
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
include(":desktopApp")
include(":backend")
