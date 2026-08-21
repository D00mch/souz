plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit.jupiterParams)
}

tasks.test {
    useJUnitPlatform()
}
