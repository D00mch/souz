plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    androidLibrary {
        namespace = "ru.souz.sharedui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        val commonMain by getting
        val commonJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/commonJvmMain/kotlin")
            resources.srcDir("src/commonJvmMain/resources")
            dependencies {
                implementation(projects.ambientAgent)
                implementation(projects.sharedLogic)
                implementation(projects.agent)
                implementation(projects.llms)

                implementation(kotlin("stdlib"))
                implementation(kotlin("reflect"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
                implementation(libs.kodein.di.framework.compose)
                implementation(libs.jackson)
                implementation(libs.slf4j.api)
            }
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.animation)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.markdown)
        }

        val androidMain by getting {
            dependsOn(commonJvmMain)
        }

        val jvmMain by getting {
            dependsOn(commonJvmMain)
            dependencies {
                implementation(projects.native)

                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.tooling.preview.desktop)
                implementation(libs.platformtools.darkmodedetector)
                implementation(libs.kotlinx.coroutinesSwing)

                implementation(libs.slfj)

                implementation(libs.jna)

                implementation(libs.java.diffUtils)
            }
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit5)
            implementation(libs.junit.jupiterParams)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "souz.sharedui.generated.resources"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "5 m")
    systemProperty("junit.jupiter.execution.timeout.mode", "enabled")
}
