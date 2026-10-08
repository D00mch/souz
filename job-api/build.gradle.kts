plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
}

dependencies {
    implementation(kotlin("stdlib"))
    api(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.test {
    useJUnitPlatform()
}
