plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":skill-oauth-api"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.bundles.ktorClient)
    implementation("io.ktor:ktor-server-core:${libs.versions.ktor.get()}")
    implementation(libs.postgresql.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.testcontainers.junitJupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.hikari.cp)
}

tasks.test {
    useJUnitPlatform()
}
