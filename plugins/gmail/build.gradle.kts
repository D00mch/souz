plugins { alias(libs.plugins.kotlinJvm) }

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlinx.coroutines)
    testImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.jar {
    archiveBaseName.set("gmail")
    manifest.attributes(
        "Souz-Plugin-Id" to "gmail",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "1",
        "Souz-Plugin-Class" to "ru.souz.plugins.gmail.GmailPlugin",
    )
}
tasks.test { useJUnitPlatform() }
