import org.gradle.api.file.DuplicatesStrategy
import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version Versions.Kotlin
    id("com.google.protobuf") version "0.9.4"
    application
}

group = "com.dumch"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.dumch.MainKt")
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        resources.srcDirs("src/main/resources", "src/main/libs")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
    // Ensure native library is discoverable during tests
    systemProperty("java.library.path", "${projectDir}/src/main/libs")
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.Coroutines}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
      implementation("ch.qos.logback:logback-classic:${Versions.Logback}")
      implementation("org.slf4j:jul-to-slf4j:2.0.17")

    // ktor
    implementation("io.ktor:ktor-client-core:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-cio:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-logging:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-content-negotiation:${Versions.Ktor}")
    implementation("io.ktor:ktor-client-auth:${Versions.Ktor}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${Versions.Ktor}")
    implementation("io.ktor:ktor-serialization-jackson:${Versions.Ktor}")

    // grpc
    implementation("io.grpc:grpc-kotlin-stub:${Versions.GrpcKotlin}")
    implementation("io.grpc:grpc-protobuf:${Versions.Grpc}")
    implementation("io.grpc:grpc-stub:${Versions.Grpc}")
    implementation("io.grpc:grpc-netty-shaded:${Versions.Grpc}")
    implementation("com.google.protobuf:protobuf-kotlin:${Versions.Protobuf}")

    // desktop manipulation
    implementation("com.github.kwhat:jnativehook:2.2.2")
    implementation("net.java.dev.jna:jna:5.14.0") // robot replacement, no jar icon will appear
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // audio
    implementation("ws.schild:jave-core:3.5.0")
    implementation("ws.schild:jave-nativebin-osxm1:3.5.0")

    // database
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.11")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${Versions.Protobuf}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${Versions.Grpc}"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${Versions.GrpcKotlin}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

tasks.named("generateProto") {
    outputs.upToDateWhen {
        val dir = file("${project.buildFile}/generated/source/proto/main/java/gigachat/v1")
        dir.exists() && dir.list()?.isNotEmpty() == true
    }
}

tasks.named("prepareKotlinBuildScriptModel") {
    dependsOn("generateProto")
}

tasks.named("compileKotlin") {
    dependsOn("generateProto")
}

tasks.named("build") {
    dependsOn("generateProto")
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}