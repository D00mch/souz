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

    testImplementation("dev.detekt:detekt-test:2.0.0-alpha.6")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("souz-detekt-rules.jar")
}
