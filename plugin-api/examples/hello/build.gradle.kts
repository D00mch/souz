plugins { kotlin("jvm") version "2.4.10" }

repositories {
    providers.environmentVariable("SOUZ_PLUGIN_MAVEN_URL").orNull?.let { repositoryUrl ->
        exclusiveContent {
            forRepository {
                maven { url = uri(repositoryUrl) }
            }
            filter { includeModule("io.github.d00mch", "plugin-api") }
        }
    }
    mavenCentral()
}
dependencies { compileOnly("io.github.d00mch:plugin-api:2.0.0") }
kotlin { jvmToolchain(21) }

tasks.jar {
    manifest.attributes(
        "Souz-Plugin-Id" to "hello",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "2;kotlin=2.4.10;coroutines=1.11.0",
        "Souz-Plugin-Jvm" to "21",
    )
}
