plugins {
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.test {
    useJUnitPlatform()
}

group = "ru.souz"
version = "1.0.0"
java { withSourcesJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }
