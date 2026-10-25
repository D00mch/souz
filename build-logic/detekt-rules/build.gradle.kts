plugins {
    kotlin("jvm")
}

group = "ru.souz.build"
version = "1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("dev.detekt:detekt-api:2.0.0-alpha.6")
    compileOnly("dev.detekt:detekt-kotlin-analysis-api:2.0.0-alpha.6")
}

tasks.jar {
    archiveFileName.set("souz-detekt-rules.jar")
}
