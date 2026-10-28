plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    `maven-publish`
}

group = "ru.souz"
version = "1.0.0"

dependencies {
    api(project(":llms"))
    api(project(":skill-oauth-api"))
    api(libs.jackson)
    api(libs.kotlinx.coroutines)
}

java { withSourcesJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }
