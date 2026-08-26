import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The embedded Python runtime costs roughly 20 MB of the APK. It is only needed for skills that
 * execute Python, so it is opt-in: build with `-Psouz.android.python=true` to bring it back.
 */
private val pythonRuntimeEnabled: Boolean =
    providers.gradleProperty("souz.android.python")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_PYTHON"))
        .orNull
        .isEnabledGradleFlag()

/** Defaults to the 32-bit ABI this project targets; override for emulators or other devices. */
private val configuredAbis: List<String> =
    providers.gradleProperty("souz.android.abis")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_ABIS"))
        .orNull
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?: listOf("armeabi-v7a")

if (pythonRuntimeEnabled) {
    apply(plugin = libs.plugins.chaquopy.get().pluginId)
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

    val signingStorePath = providers.gradleProperty("souz.android.signing.storeFile")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_SIGNING_STORE_FILE"))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val signingStorePassword = providers.gradleProperty("souz.android.signing.storePassword")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_SIGNING_STORE_PASSWORD"))
        .orNull
    val signingKeyAlias = providers.gradleProperty("souz.android.signing.keyAlias")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_SIGNING_KEY_ALIAS"))
        .orNull
    val signingKeyPassword = providers.gradleProperty("souz.android.signing.keyPassword")
        .orElse(providers.environmentVariable("SOUZ_ANDROID_SIGNING_KEY_PASSWORD"))
        .orNull

    val configuredSigning = signingStorePath
        ?.let { path ->
            val store = File(path)
            if (!store.isFile || !store.canRead()) {
                throw GradleException("Configured Android signing store is not readable: $path")
            }
            if (signingStorePassword.isNullOrBlank()) {
                throw GradleException("Configured Android signing store password is blank")
            }
            if (signingKeyAlias.isNullOrBlank()) {
                throw GradleException("Configured Android signing key alias is blank")
            }
            signingConfigs.create("configuredDebug") {
                storeFile = store
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword ?: signingStorePassword
            }
        }

    defaultConfig {
        applicationId = "ru.souz.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += configuredAbis
        }
    }

    sourceSets.named("main") {
        val variant = if (pythonRuntimeEnabled) "pythonRuntime" else "noPython"
        kotlin.srcDir("src/$variant/kotlin")
    }

    sourceSets.named("androidTest") {
        if (pythonRuntimeEnabled) {
            kotlin.srcDir("src/pythonRuntime/androidTest/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    configuredSigning?.let { signing ->
        buildTypes.named("debug") {
            signingConfig = signing
        }
    }
}

if (pythonRuntimeEnabled) extensions.configure<com.chaquo.python.ChaquopyExtension>("chaquopy") {
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
