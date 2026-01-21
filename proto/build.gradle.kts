import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    alias(libs.plugins.protobuf)
}

sourceSets {
    main {
        proto {
            srcDir("../composeApp/src/jvmMain/proto")
        }
    }
}

dependencies {
    api(libs.grpc.kotlinStub)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.protobuf.kotlin)
    api(libs.kotlinx.coroutines)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
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
