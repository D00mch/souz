plugins { alias(libs.plugins.kotlinJvm) }

dependencies {
    compileOnly(project(":plugin-api"))
    implementation(libs.jsoup)
    testImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.jar {
    archiveBaseName.set("gmail")
    // Only the private HTML parser is bundled; shared SDK/runtime classes stay in the host.
    from(configurations.runtimeClasspath.map { files -> files.filter { it.name.startsWith("jsoup-") }.map(::zipTree) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "Souz-Plugin-Id" to "gmail",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "1",
        "Souz-Plugin-Class" to "ru.souz.plugins.gmail.GmailPlugin",
    )
}
tasks.test { useJUnitPlatform() }
