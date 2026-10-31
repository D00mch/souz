plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    runtimeOnly(libs.imageio.webp)
    implementation(projects.agent)
    implementation(projects.llms)
    implementation(projects.skillOauthApi)
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.bundles.ktorClient)
    implementation(libs.ktor.clientOkhttp)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation(libs.jsoup)
    implementation(libs.re2j)
    implementation(libs.slf4j.api)
    implementation(libs.commons.csv)
    implementation(libs.tika.core)
    implementation(libs.tika.parsersStandardPackage)
    implementation(libs.jfreechart)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.logback)
    implementation(libs.slfj)
    implementation(libs.slf4j.jcl)
    implementation(libs.log4j.to.slf4j)

    testImplementation(projects.graphEngine)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.mockk)
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.register<Exec>("buildRuntimeSandboxImage") {
    group = "docker"
    description = "Builds the Docker runtime sandbox image."
    commandLine(
        providers.environmentVariable("SOUZ_DOCKER_CLI").orElse("docker").get(),
        "build", "-t", "souz-runtime-sandbox:latest", projectDir.absolutePath,
    )
}
