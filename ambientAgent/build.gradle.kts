plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.llms)
    implementation(projects.sharedLogic)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.slfj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.test {
    useJUnitPlatform()
}
