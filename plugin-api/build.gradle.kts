plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    `maven-publish`
}

group = "ru.souz"
version = "2.0.0"

dependencies {
    api(libs.kotlinx.coroutines)
}

java { withSourcesJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }
