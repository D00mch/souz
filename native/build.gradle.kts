plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.llms)
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.jna)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit.jupiterParams)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.test {
    useJUnitPlatform()
}
