plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.agent)
    implementation(projects.llms)
    implementation(projects.native)
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jackson)
    implementation(libs.ktor.serializationJackson)
    implementation(libs.bundles.ktorClient)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation(libs.commons.csv)
    implementation(libs.tika.core)
    implementation(libs.tika.parsersStandardPackage)
    implementation(libs.java.diffUtils)
    implementation(libs.bundles.letsPlot)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.jsoup)
    implementation(libs.slfj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}
