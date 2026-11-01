plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
}

dependencies {
    api(project(":llms"))
    api(libs.kotlinx.coroutines)
    api(libs.jackson.databind)
    implementation(kotlin("stdlib"))
}
