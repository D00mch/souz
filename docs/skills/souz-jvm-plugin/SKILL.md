---
name: souz-jvm-plugin
description: Build or update a trusted Souz JVM tool plugin in a standalone Java or Kotlin project, from tool requirements and JSON schemas to a tested JAR for operator installation.
---

# Build a Souz JVM plugin

Confirm the target Souz version, plugin ID and version, and the tools' inputs, results, and error behavior. Use the project's Java or Kotlin setup. For an empty Kotlin project, start from the standalone Hello layout.

Use the [SDK contract](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/plugin-api/docs/plugin-contract.md), [Hello project](https://github.com/D00mch/souz/tree/plugin-api-v2.0.0/plugin-api/examples/hello), and [SDK signatures](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/plugin-api/src/main/kotlin/ru/souz/plugins/api/Plugin.kt) as references for the 2.0.0 SDK. Match the released SDK and its ABI to the target Souz host.

## Set up the project

Use JDK 21. In a Gradle project without a wrapper, run `gradle wrapper --gradle-version 9.4.1` with an installed Gradle. Use the generated wrapper for builds.

In a Java-only project, apply the `java` plugin. In Kotlin, apply the Kotlin JVM plugin at the Kotlin version in the target SDK's ABI. Configure the JVM toolchain for 21.

Resolve the SDK from Maven Central. For a host compatible with SDK 2.0.0, use this Gradle Kotlin DSL:

```kotlin
repositories { mavenCentral() }
dependencies { compileOnly("io.github.d00mch:plugin-api:2.0.0") }
```

## Implement and register the tools

- Implement one public no-argument `ru.souz.plugins.api.SouzPlugin` provider. Expose immutable `PluginTool` metadata and stateless handlers. Keep construction and tool metadata free of I/O and other side effects.
- Give each tool a name, a description, and a case-sensitive [tool category](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/agent/src/main/kotlin/ru/souz/tool/ToolCategory.kt). Plugin IDs and tool names match `[a-z][a-z0-9_]{0,39}`. Keep them short enough that `plugin.<pluginId>.<toolId>` fits in 64 characters.
- Read the JSON argument string and return valid JSON. Use the invocation's `PluginContext` for host services and follow the contract's cancellation and owner-scoping rules. Keep invocation state within the handler.
- In Kotlin, use the suspending lambda shown in Hello. In Java, implement `getTools()` and pass a three-argument handler `(arguments, context, continuation) -> ...` to the `PluginTool` constructor. A synchronous handler returns its JSON string directly. For a suspending service, follow the continuation pattern in the [Java provider fixture](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/backend/src/test/fixtures/javaPlugin/java/fixture/JavaPlugin.java).
- Put the fully qualified provider class name on its own line in `src/main/resources/META-INF/services/ru.souz.plugins.api.SouzPlugin`.

### Tool input schemas

Set the root `type` to `object`. For objects, define named `properties`, list mandatory property names in `required`, and set `additionalProperties` to a boolean when needed.

Each nested schema has a single `type` selected from `object`, `array`, `string`, `integer`, `number`, `boolean`, and `null`. Arrays require one `items` schema. Nested schemas may have a `description`, and strings may have a nonempty `enum` of strings. Nesting is limited to 16 levels below the root.

Leave `oneOf`, `anyOf`, `$ref`, `$defs`, `pattern`, and `format` out of `inputSchemaJson`. Flatten alternative shapes into supported fields, write nested schemas inline, and validate extra constraints in the handler.

## Package, test, and hand off

Set these attributes in `META-INF/MANIFEST.MF` using the [Hello Gradle build](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/plugin-api/examples/hello/build.gradle.kts) as a template:

| Attribute | Value |
| --- | --- |
| `Souz-Plugin-Id` | Plugin ID |
| `Souz-Plugin-Version` | Version of this plugin artifact |
| `Souz-Plugin-Api` | Exact `PluginApi.ABI` string in `Plugin.kt` from the matching SDK sources |
| `Souz-Plugin-Jvm` | `21` |

Souz binds each installed plugin ID and version to one artifact digest. Bump `Souz-Plugin-Version` before installing changed JAR bytes, even for a minor fix. Reusing an installed version with a different digest returns `plugin_version_conflict`.

Use an ordinary JAR when the plugin needs no private libraries. If it needs private libraries, bundle their classes and resources in a flat JAR and merge any private service descriptors. Keep the SDK, Kotlin standard library, and coroutines out of the JAR. Souz provides them at runtime. Omit manifest `Class-Path`. See the [Kotlin Shadow example](#kotlin-example-with-a-private-dependency).

Run `./gradlew test jar` for an ordinary JAR or `./gradlew test shadowJar` for a Shadow build. On Windows, use `gradlew.bat`. Test representative inputs and the expected JSON results. If tests need the SDK at runtime, add the same SDK version to `testImplementation`. Inspect the JAR for the manifest, provider class, service registration, and absence of host-provided classes.

Give the operator the JAR, plugin ID and version, ABI, tool names, and a sample invocation with its expected result.

On an authorized test host, follow the [operator installation procedure](https://github.com/D00mch/souz/blob/plugin-api-v2.0.0/backend/docs/jvm-plugins.md#configuration-and-administration) with the built JAR. Use the current registry revision, or `0` for a new plugin ID. Wait for the serving replica's applied digest to match the install response. Include `plugin.<pluginId>.<toolId>` in the test user's `enabledTools` and check the result from a Souz turn that invokes it.

## Kotlin example with a private dependency

Copy the standalone Hello project. Keep its settings and provider registration and replace `build.gradle.kts` with the following.

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories { mavenCentral() }

dependencies {
    compileOnly("io.github.d00mch:plugin-api:2.0.0")
    implementation("org.apache.commons:commons-lang3:3.22.0")
}

kotlin { jvmToolchain(21) }

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*:.*"))
    }
    manifest.attributes(
        "Souz-Plugin-Id" to "hello_private",
        "Souz-Plugin-Version" to "1.0.0",
        "Souz-Plugin-Api" to "2;kotlin=2.4.10;coroutines=1.11.0",
        "Souz-Plugin-Jvm" to "21",
    )
}
```

Replace the last expression of the Hello handler with the following to exercise the private library.

```kotlin
val greeting = org.apache.commons.lang3.StringUtils.capitalize("hello from a private library")
"""{"message":"$greeting"}"""
```

Build with `./gradlew shadowJar`. Install `build/libs/souz-plugin-hello-all.jar` and call `plugin.hello_private.hello` with `{}`. Expect `{"message":"Hello from a private library"}`.
