plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    `maven-publish`
}

group = "ru.souz"
version = "1.1.0"

dependencies {
    api(libs.jackson.databind)
    api(kotlin("stdlib"))
    constraints {
        api(libs.kotlinx.coroutines)
    }
}

java { withSourcesJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }
