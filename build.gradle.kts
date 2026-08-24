import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("souz.quality")
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

if (providers.gradleProperty("souz.coverage").isPresent) {
    allprojects.forEach { coverageProject ->
        coverageProject.pluginManager.apply("org.jetbrains.kotlinx.kover")
        if (coverageProject != rootProject) {
            dependencies.add("kover", project(coverageProject.path))
        }

        coverageProject.extensions.configure<KoverProjectExtension> {
            reports {
                total {
                    filters {
                        excludes {
                            classes("*.generated.resources.*")
                        }
                    }
                    log {
                        format = if (coverageProject == rootProject) {
                            "SOUZ_KOVER_LINE_COVERAGE=<value>%"
                        } else {
                            "SOUZ_KOVER_MODULE_LINE_COVERAGE=${coverageProject.path}=<value>%"
                        }
                        groupBy = GroupingEntityType.APPLICATION
                        coverageUnits = CoverageUnit.LINE
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
        }
    }
}

val souzJvmToolchainVersion = 21
val souzJvmLanguageVersion = JavaLanguageVersion.of(souzJvmToolchainVersion)

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(souzJvmToolchainVersion)
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension>("kotlin") {
            jvmToolchain(souzJvmToolchainVersion)
        }
    }

    tasks.withType<Test>().configureEach {
        val toolchains = project.extensions.getByType<JavaToolchainService>()
        javaLauncher.set(
            toolchains.launcherFor {
                languageVersion.set(souzJvmLanguageVersion)
            }
        )
    }
}
