plugins { alias(libs.plugins.kotlinJvm) }

dependencies {
    compileOnly(project(":plugin-api"))
    testImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.jar {
    archiveBaseName.set("saved-links")
    manifest.attributes(
        "Souz-Plugin-Id" to "saved_links",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "1",
        "Souz-Plugin-Class" to "ru.souz.plugins.links.SavedLinksPlugin",
    )
}
tasks.test { useJUnitPlatform() }
