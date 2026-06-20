plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    jvm()
    androidLibrary {
        namespace = "ru.souz.ambientagent"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

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

        val androidMain by getting {
            dependsOn(commonJvmMain)
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
