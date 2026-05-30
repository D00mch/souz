plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

fun skikoAwtRuntimeModule(): String {
    val osName = System.getProperty("os.name", "").lowercase()
    val osArch = System.getProperty("os.arch", "").lowercase()
    val arch = if (osArch.contains("aarch64") || osArch.contains("arm64")) "arm64" else "x64"
    val os = when {
        osName.contains("mac") -> "macos"
        osName.contains("win") -> "windows"
        osName.contains("linux") -> "linux"
        else -> error("Unsupported OS for skiko runtime: $osName ($osArch)")
    }
    return "org.jetbrains.skiko:skiko-awt-runtime-$os-$arch:0.9.22.2"
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

kotlin {
    jvm()
    androidLibrary {
        namespace = "ru.souz.sharedlogic"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(projects.agent)
                implementation(projects.llms)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.jackson)
                implementation(libs.ktor.serializationJackson)
                implementation(libs.bundles.ktorClient)
                implementation(libs.slf4j.api)
            }
        }

        val jvmMain by getting {
            kotlin.srcDir("src/jvmMain/kotlin")
            resources.srcDir("src/jvmMain/resources")
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
                implementation(libs.lucene.core)
                implementation(libs.slfj)
                implementation(libs.logback)
                implementation(libs.log4j.to.slf4j)
                implementation("org.jetbrains.skiko:skiko-awt:0.9.22.2")
                runtimeOnly(skikoAwtRuntimeModule())
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.testJunit5)
                implementation(libs.kotlinx.coroutinesTest)
                implementation(libs.mockk)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<Exec>("buildRuntimeSandboxImage") {
    group = "docker"
    description = "Builds the Docker runtime sandbox image used by local runs."
    commandLine(dockerCli.get(), "build", "-t", runtimeSandboxImageName, projectDir.absolutePath)
}
