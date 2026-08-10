plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting
        val commonJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/commonJvmMain/kotlin")
            dependencies {
                implementation(kotlin("stdlib"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.slf4j.api)
            }
        }

        val jvmMain by getting {
            dependsOn(commonJvmMain)
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                implementation(projects.sharedLogic)
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.testJunit5)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
