import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":llms"))
    implementation(project(":native"))
    implementation(project(":runtime"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.ktor.serializationJackson)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.get()}")
    implementation(libs.logback)
    implementation(libs.slfj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

application {
    mainClass.set("ru.souz.backend.BackendMainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Sync>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
