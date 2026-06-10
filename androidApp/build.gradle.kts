import com.android.build.api.dsl.ApplicationExtension
import java.io.File

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

private val chaquopyPythonVersion = "3.11"
private val disabledChaquopyBuildPythonPath =
    layout.buildDirectory.file("disabled-chaquopy-build-python/python").get().asFile.absolutePath

private fun String?.isEnabledGradleFlag(): Boolean =
    when (this?.trim()?.lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        else -> false
    }

private fun executableOnPath(command: String): String? =
    System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, command) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

private fun executableFile(path: String): String? =
    File(path).takeIf { it.isFile && it.canExecute() }?.absolutePath

private fun detectedChaquopyBuildPython(version: String): String? =
    sequenceOf(
        executableOnPath("python$version"),
        executableFile("/opt/homebrew/opt/python@$version/bin/python$version"),
        executableFile("/opt/homebrew/bin/python$version"),
        executableFile("/usr/local/opt/python@$version/bin/python$version"),
        executableFile("/usr/local/bin/python$version"),
        executableFile("/Library/Frameworks/Python.framework/Versions/$version/bin/python$version"),
    ).firstOrNull { it != null }

extensions.configure<ApplicationExtension>("android") {
    namespace = "ru.souz.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ru.souz.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

chaquopy {
    defaultConfig {
        version = chaquopyPythonVersion
        val bundlePythonRequirements = providers.gradleProperty("souz.android.bundlePythonRequirements")
            .orElse(providers.environmentVariable("SOUZ_ANDROID_BUNDLE_PYTHON_REQUIREMENTS"))
            .orNull
            .isEnabledGradleFlag()
        if (bundlePythonRequirements) {
            val configuredBuildPython = providers.gradleProperty("souz.android.buildPython")
                .orElse(providers.environmentVariable("SOUZ_ANDROID_BUILD_PYTHON"))
                .orNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            (configuredBuildPython ?: detectedChaquopyBuildPython(chaquopyPythonVersion))?.let {
                buildPython(it)
            }
            pip {
                install("lxml==5.3.0")
                install("Pillow==11.0.0")
                install("XlsxWriter==3.2.9")
                install("python-pptx==1.0.2")
            }
        } else {
            buildPython(disabledChaquopyBuildPythonPath)
        }
        pyc {
            src = false
        }
    }
}

dependencies {
    implementation(projects.agent)
    implementation(projects.llms)
    implementation(projects.sharedLogic)
    implementation(projects.sharedUI)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutinesAndroid)
    implementation(libs.jackson)
    implementation(libs.slf4j.api)
    implementation("org.kodein.di:kodein-di:${libs.versions.kodeinDi.get()}")
    implementation(kotlin("stdlib-jdk8"))
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
