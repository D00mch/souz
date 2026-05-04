plugins {
    alias(libs.plugins.kotlinJvm)
}

val runtimeSandboxImageName = "souz-runtime-sandbox:latest"
val dockerCli = providers.environmentVariable("SOUZ_DOCKER_CLI")
    .orElse(
        providers.provider {
            listOf("/opt/homebrew/bin/docker", "/usr/local/bin/docker")
                .firstOrNull { file(it).canExecute() }
                ?: "docker"
        }
    )

dependencies {
    implementation(projects.agent)
    implementation(projects.llms)
    implementation(projects.native)
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.bundles.ktorClient)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation(libs.commons.csv)
    implementation(libs.tika.core)
    implementation(libs.tika.parsersStandardPackage)
    implementation(libs.java.diffUtils)
    implementation(libs.bundles.letsPlot)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.jsoup)
    implementation(libs.slfj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Exec>("buildRuntimeSandboxImage") {
    group = "docker"
    description = "Builds the Docker runtime sandbox image used by local runs."
    commandLine(dockerCli.get(), "build", "-t", runtimeSandboxImageName, projectDir.absolutePath)
}
