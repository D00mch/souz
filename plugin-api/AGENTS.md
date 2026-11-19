# Plugin API

`:plugin-api` defines the Java/Kotlin ABI for trusted stateless JVM plugins.
Keep signatures limited to SDK-owned values, JDK/Kotlin types, and suspending methods.

## Pain points

- [SDK contract](docs/plugin-contract.md) — ABI, packaging, isolation, services,
  serialization, and cancellation.

## Verification

```bash
python3 -m unittest discover -s scripts -p 'test_plugin_sdk_release.py'
./gradlew :plugin-api:check :plugin-api:publishSdkPublicationToVerificationRepository :backend:test
bash scripts/verify-plugin-sdk.sh
```

Set `SOUZ_PLUGIN_MAVEN_URL` to the verification repository's file URI to test staged artifacts;
leave it unset to test Maven Central. The verifier copies the example outside the
checkout and builds with fresh Gradle and Maven local directories.
