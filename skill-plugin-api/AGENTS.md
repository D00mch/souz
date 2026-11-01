# Skill plugin API

Small Java/Kotlin JVM contract for trusted in-process Skills. Runtime implementation belongs to
`:backend`; this module depends only on `:llms`. Public signatures use host-aligned Kotlin,
coroutines and Jackson versions. Do not expose backend DI, storage or implementation types.

## Pain points

The SDK is compile-only in plugin artifacts. Private dependencies and service resources belong
to the plugin JAR; shared ABI classes must not be shaded into it.

## Verification

Verify with `./gradlew :skill-plugin-api:build :backend:test`.
