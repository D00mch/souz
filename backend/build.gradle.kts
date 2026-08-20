    import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.testRetry)
    application
}

ktor {
    openApi {
        enabled.set(true)
        codeInferenceEnabled.set(true)
        // Full inference outlines deferred descriptions and breaks their reified schema helpers.
        onlyCommented.set(true)
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":llms"))
    implementation(project(":native"))
    implementation(project(":sharedLogic"))
    implementation(project(":skill-oauth-api"))
    implementation(project(":skill-oauth-impl"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikari.cp)
    implementation(libs.ktor.openapiSchemaReflect)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.ktor.serverRoutingOpenapi)
    implementation(libs.ktor.serverSwagger)
    implementation(libs.postgresql.jdbc)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-status-pages:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-websockets:${libs.versions.ktor.get()}")
    implementation(libs.logback)
    implementation(libs.slfj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.mockk)
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-websockets:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation(libs.testcontainers.junitJupiter)
    testImplementation(libs.testcontainers.postgresql)
}

application {
    mainClass.set("ru.souz.backend.app.BackendMainKt")
}

tasks.test {
    useJUnitPlatform()
    retry {
        // BackendPublicClientContractRouteTest's "thread cancel is acknowledged before the
        // cancelled terminal event" flakes intermittently under CI scheduling load (see #639,
        // the withTimeout budget was already raised 2s -> 5s once and it wasn't enough). No
        // per-method filter is available — includeClasses only matches by class name — so this
        // scopes to the whole class; retries only ever kick in on an actual failure, so passing
        // tests in the class are unaffected.
        maxRetries.set(5)
        filter {
            includeClasses.add("ru.souz.backend.http.BackendPublicClientContractRouteTest")
        }
    }
}

tasks.withType<Sync>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
