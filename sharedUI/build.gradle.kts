plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
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
        }

        jvmMain.dependencies {
            implementation(projects.sharedLogic)
            implementation(projects.agent)
            implementation(projects.llms)
            implementation(projects.native)

            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.tooling.preview.desktop)
            implementation(libs.kodein.di.framework.compose)
            implementation(libs.platformtools.darkmodedetector)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesSwing)

            implementation(kotlin("reflect"))

            implementation(libs.jackson)
            implementation(libs.slfj)

            implementation(libs.jna)

            implementation(libs.markdown)
            implementation(libs.java.diffUtils)
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
