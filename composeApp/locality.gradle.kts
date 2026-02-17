import org.gradle.api.tasks.testing.Test
import org.gradle.process.JavaForkOptions

val edition = providers.gradleProperty("edition").orNull?.trim()?.lowercase().orEmpty().ifBlank { "ru" }
require(edition in setOf("ru", "en")) {
    "Unsupported edition '$edition'. Use -Pedition=ru or -Pedition=en."
}

val editionPackageName = if (edition == "ru") "Союз ИИ" else "Souz AI"
val editionBundleId = if (edition == "ru") "ru.gigadesk" else "en.gigadesk"
val editionDockName = if (edition == "ru") "Союз c ИИ" else "Souz AI"

extra["edition"] = edition
extra["editionPackageName"] = editionPackageName
extra["editionBundleId"] = editionBundleId
extra["editionDockName"] = editionDockName

tasks.matching { it.name == "jvmRun" }.configureEach {
    if (this is JavaForkOptions) {
        systemProperty("gigadesk.edition", edition)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("gigadesk.edition", edition)
}

tasks.register("packageRuReleaseDmg") {
    group = "distribution"
    description = "Build RU release DMG. Run with -Pedition=ru."
    dependsOn("packageReleaseDmg")
    doFirst {
        require(edition == "ru") { "packageRuReleaseDmg must be run with -Pedition=ru." }
    }
}

tasks.register("packageEnReleaseDmg") {
    group = "distribution"
    description = "Build EN release DMG. Run with -Pedition=en."
    dependsOn("packageReleaseDmg")
    doFirst {
        require(edition == "en") { "packageEnReleaseDmg must be run with -Pedition=en." }
    }
}
