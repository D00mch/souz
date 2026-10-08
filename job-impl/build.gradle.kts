plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":job-api"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.cron.utils)
    implementation(libs.postgresql.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.testcontainers.junitJupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.hikari.cp)
}

tasks.test {
    useJUnitPlatform()
    if (System.getenv("DOCKER_HOST").isNullOrBlank()) {
        val dockerDesktopSocket = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
        if (dockerDesktopSocket.exists()) {
            environment("DOCKER_HOST", "unix://${dockerDesktopSocket.absolutePath}")
        }
    }
}
