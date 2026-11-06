plugins { alias(libs.plugins.kotlinJvm) }

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.jsoup)
    testImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.jar {
    archiveBaseName.set("gmail")
    // JSON and HTML libraries are private; the SDK, Kotlin and coroutines belong to the host.
    from(configurations.runtimeClasspath.map { files -> files.filterNot {
        it.name.startsWith("kotlin-") || it.name.startsWith("kotlinx-coroutines-")
    }.map(::zipTree) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "Souz-Plugin-Id" to "gmail",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "2;kotlin=${libs.versions.kotlin.get()};coroutines=${libs.versions.kotlinx.coroutines.get()}",
        "Souz-Plugin-Jvm" to "21",
    )
}
tasks.test { useJUnitPlatform() }
