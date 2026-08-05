plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.test {
    useJUnitPlatform()
}
