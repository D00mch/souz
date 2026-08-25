rootProject.name = "build-logic"

include(":detekt-rules")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
