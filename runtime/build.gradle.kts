plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.agent)
    implementation(projects.llms)
    implementation(projects.native)
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.bundles.ktorClient)
    implementation(libs.slfj)
}

tasks.test {
    useJUnitPlatform()
}
