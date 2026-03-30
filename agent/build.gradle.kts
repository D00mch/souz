plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.logback)
    implementation(libs.luaj.jse)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit.jupiterParams)
}

tasks.test {
    useJUnitPlatform()
}
