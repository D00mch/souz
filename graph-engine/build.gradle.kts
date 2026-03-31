plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit.jupiterParams)
}

tasks.test {
    useJUnitPlatform()
}
