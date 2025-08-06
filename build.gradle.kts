plugins {
    kotlin("jvm") version Versions.Kotlin
}

group = "com.dumch"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.Coroutines}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // ktor
    implementation("io.ktor:ktor-client-core:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-cio:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-content-negotiation:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-auth:${Versions.Ktor}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${Versions.Ktor}")
    implementation("io.ktor:ktor-serialization-jackson:${Versions.Ktor}")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
