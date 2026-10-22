plugins { alias(libs.plugins.kotlinJvm) }

dependencies {
    compileOnly(project(":plugin-api"))
    testImplementation(project(":plugin-runtime"))
    testImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

val pluginJar = tasks.named<Jar>("jar") {
    archiveBaseName.set("github")
    manifest.attributes(mapOf(
        "Souz-Plugin-Id" to "github",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "1",
        "Souz-Plugin-Class" to "ru.souz.plugins.github.GitHubPlugin",
    ))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(pluginJar)
    systemProperty("github.plugin.jar", pluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    // Exercise JAR loading rather than resolving plugin classes from the test host.
    classpath = classpath.minus(sourceSets.main.get().output)
}
