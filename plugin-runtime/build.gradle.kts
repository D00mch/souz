plugins { alias(libs.plugins.kotlinJvm); `java-test-fixtures` }

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.kotlinx.coroutines)
    testFixturesImplementation(project(":plugin-api"))
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.test { useJUnitPlatform() }
