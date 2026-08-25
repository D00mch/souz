plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
}

group = "ru.souz.build"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.2")
}

gradlePlugin {
    plugins {
        create("souzQuality") {
            id = "souz.quality"
            implementationClass = "ru.souz.build.quality.SouzQualityPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(":detekt-rules:check")
}
