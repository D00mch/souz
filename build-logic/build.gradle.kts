plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "ru.souz.build"

val kotlinGradlePluginVersion = "2.4.10"

val functionalTestKotlinPluginClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":detekt-rules"))
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$kotlinGradlePluginVersion")

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.2")

    functionalTestKotlinPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinGradlePluginVersion")
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
    inputs.files(functionalTestKotlinPluginClasspath)
    doFirst {
        systemProperty("souz.test.kotlin-plugin-classpath", functionalTestKotlinPluginClasspath.asPath)
    }
}

tasks.named("check") {
    dependsOn(":detekt-rules:check")
}
