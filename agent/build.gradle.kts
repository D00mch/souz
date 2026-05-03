plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.graphEngine)
    implementation(projects.llms)
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.logback)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit.jupiterParams)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(projects.runtime)
}

tasks.test {
    useJUnitPlatform()
}
