plugins { kotlin("jvm") version "2.4.10" }
repositories { mavenLocal(); mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies { compileOnly("ru.souz:plugin-api:1.0.0") }
tasks.jar {
    archiveFileName.set("hello.jar")
    manifest.attributes(
        "Souz-Plugin-Id" to "hello",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "1",
        "Souz-Plugin-Class" to "example.HelloPlugin",
    )
}
