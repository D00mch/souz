    import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
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
    implementation(project(":sharedLogic"))
    implementation(project(":skill-oauth-api"))
    implementation(project(":skill-plugin-api"))
    implementation(project(":skill-oauth-impl"))
    implementation(project(":job-api"))
    implementation(project(":job-impl"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutinesSlf4j)
    implementation(libs.jackson)
    implementation(libs.commonmark)
    implementation(libs.cron.utils)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikari.cp)
    implementation("io.ktor:ktor-server-metrics-micrometer:${libs.versions.ktor.get()}")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.3")
    implementation(libs.ktor.openapiSchemaReflect)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.ktor.serverRoutingOpenapi)
    implementation(libs.ktor.serverSwagger)
    implementation(libs.postgresql.jdbc)
    implementation(libs.aws.s3)
    implementation(libs.aws.urlConnectionClient)
    implementation(libs.tika.core)
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
    testImplementation(libs.commons.csv)
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-websockets:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation(libs.testcontainers.junitJupiter)
    testImplementation(libs.testcontainers.postgresql)
}

application {
    mainClass.set("ru.souz.backend.app.BackendMainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
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

tasks.named<Jar>("shadowJar") {
    isZip64 = true
}

// Real plugin artifacts stay off the host test runtime classpath.
val pluginSdkAbi = "1;kotlin=${libs.versions.kotlin.get()};coroutines=${libs.versions.kotlinx.coroutines.get()};jackson=${libs.versions.jackson.get()}"

fun pluginFixture(name: String, directory: String, id: String, version: String, csv: String? = null) {
    val fixture = sourceSets.create("${name}PluginTestFixtures") {
        java.setSrcDirs(listOf("src/$directory/java"))
        resources.setSrcDirs(listOf("src/$directory/resources"))
    }
    kotlin.sourceSets.getByName(fixture.name).kotlin.srcDir("src/$directory/kotlin")
    dependencies.add(fixture.compileOnlyConfigurationName, project(":skill-plugin-api"))
    configurations[fixture.runtimeClasspathConfigurationName].exclude(group = "org.jetbrains.kotlin")
    if (csv != null) dependencies.add(fixture.implementationConfigurationName, "org.apache.commons:commons-csv:$csv")
    val artifact = tasks.register<Jar>("${name}PluginJar") {
        archiveBaseName.set("plugin-$name")
        from(fixture.output)
        if (csv != null) from(configurations[fixture.runtimeClasspathConfigurationName].map { zipTree(it) })
        manifest.attributes(
            "Souz-Plugin-Id" to id,
            "Souz-Plugin-Version" to version,
            "Souz-Plugin-Api" to pluginSdkAbi,
            "Souz-Plugin-Jvm" to "21",
        )
        if (csv != null) from(resources.text.fromString(version)) { rename { "fixture-version.txt" } }
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
    tasks.test {
        dependsOn(artifact)
        systemProperty("plugin.fixture." + when (name) {
            "kotlinV1" -> "kotlin-v1"
            "kotlinV2" -> "kotlin-v2"
            else -> name
        }, artifact.flatMap { it.archiveFile }.get().asFile.absolutePath)
    }
}

pluginFixture("java", "javaPluginTestFixtures", "java_reference", "v1")
pluginFixture("kotlinV1", "kotlinPluginTestFixtures", "reference", "v1", "1.10.0")
pluginFixture("kotlinV2", "kotlinPluginTestFixtures", "reference", "v2", "1.9.0")
