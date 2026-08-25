rootProject.name = "build-logic"

include(":detekt-rules")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    resolutionStrategy {
        eachPlugin {
            val version = target.version ?: requested.version ?: return@eachPlugin
            if (requested.id.id == "org.jetbrains.kotlin.jvm") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$version")
            }
        }
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
