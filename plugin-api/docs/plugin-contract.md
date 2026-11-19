# Trusted JVM plugin SDK

Operators install reviewed Java 21 code that runs with backend process privileges.
Classloaders isolate dependencies. Plugins share process resources and can exhaust them.
The host owns resources and invokes stateless handlers.
Keep constructors and metadata side-effect-free. Do not retain invocation contexts, create
threads or launch detached work. Await structured coroutines and propagate cancellation.

## Build and package

Use Java 21 and resolve released SDK versions from Maven Central
(`https://repo.maven.apache.org/maven2`), without credentials:

```kotlin
repositories { mavenCentral() }
dependencies { compileOnly("io.github.d00mch:plugin-api:2.0.0") }
```

Copy the [Hello project](../examples/hello/) into an independent Gradle project and run
`./gradlew jar` with that project's Gradle wrapper. The example uses Gradle 9.4.1.
No Souz checkout or `publishToMavenLocal` step is required.

Use the [hello build](../examples/hello/build.gradle.kts) for the compile-only SDK dependency,
Java toolchain and required manifest attributes. Implement one public no-argument
[`SouzPlugin` provider](../examples/hello/src/main/kotlin/example/HelloPlugin.kt) and register it
in [`META-INF/services/ru.souz.plugins.api.SouzPlugin`](../examples/hello/src/main/resources/META-INF/services/ru.souz.plugins.api.SouzPlugin).
The [SDK signatures](../src/main/kotlin/ru/souz/plugins/api/Plugin.kt) define the ABI fingerprint
and stateless suspending handlers with JSON object input schemas, JSON arguments and JSON results.
The host exposes names as `plugin.<pluginId>.<toolId>` in the declared domain category.
Use the [operator commands](../../backend/docs/jvm-plugins.md#configuration-and-administration) to install and verify rollout.

Keep the SDK, Kotlin and coroutines compile-only. Bundle private dependencies and their resources
and service providers in a flat JAR. External manifest `Class-Path` entries are unsupported.
Private classes and resources have no backend classpath fallback. The plugin context classloader
follows suspension. Keep reflection and serializer caches private to each loaded version.
Avoid storing plugin objects in shared-worker thread locals. For Jackson, use a mapper with
`JsonRecyclerPools.nonRecyclingPool()` and serialize nodes through that mapper. `JsonNode.toString()`
and `toPrettyString()` use the default recycler, which can retain retired classloaders.
[Plugin classloader](../../backend/src/main/kotlin/ru/souz/backend/plugins/PluginClassLoader.kt)

## SDK releases

The Maven group is `io.github.d00mch`; Kotlin package names remain `ru.souz.plugins.api`.
The [publication](../build.gradle.kts) includes the binary JAR, POM, sources and Javadoc JARs.
Maven Central releases are immutable: increment the SDK version for each release and update
the Hello dependency. The plugin ABI fingerprint changes only when its compatibility changes.

Before the first release, the `D00mch` account owner must:

1. Sign in to [Sonatype Central](https://central.sonatype.com/) through GitHub and verify the
   `io.github.d00mch` namespace. A separate DNS domain is not required.
2. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/).
3. Create a [signing key and publish its public key](https://central.sonatype.org/publish/requirements/gpg/).
4. Create the GitHub Actions environment `plugin-sdk-release` with trusted release reviewers,
   enable **Prevent self-review**, and disable administrator bypass. Allow only tags matching
   `plugin-api-v*` under **Selected branches and tags**. Store the following secrets only in
   that environment; remove any repository or organization copies available to this repository
   so other workflows cannot bypass release approval. Do not commit credentials or keys.

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Generated Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Generated Central Portal token password |
| `MAVEN_SIGNING_KEY` | ASCII-armored private signing key |
| `MAVEN_SIGNING_PASSWORD` | Signing key passphrase, if set |

Push a tag matching the SDK version, such as `plugin-api-v2.0.0`, at a reviewed commit in
`origin/kmp` with passing CI to run the
[release workflow](../../.github/workflows/publish-plugin-sdk.yml). Its separate preflight job
rejects missing approval protection, moved tags, and commits outside `kmp` history before the
publishing job starts. A release reviewer must check the tagged commit and CI before approving
the environment deployment. Both jobs check out the triggering commit by SHA. The publishing
job uses pinned actions, downloads a fresh Temurin JDK with mandatory signature verification,
and disables Gradle cache restoration before verifying a staged
publication, signing and releasing the SDK, waiting for Maven Central availability, and building
the standalone Hello project against the public repository. Downloading released SDK versions
does not require the publishing token or signing key. Keep
[#1046](https://github.com/D00mch/souz/issues/1046) open until the first public release and
standalone verification succeed.

Gradle checks downloaded plugins, dependencies and their metadata against the committed
[SHA-256 verification metadata](../../gradle/verification-metadata.xml). Both release Gradle
invocations require strict verification, including the invocation receiving signing and Central
credentials. Missing or changed hashes fail the build. For dependency updates, generate candidate
hashes with `./gradlew --write-verification-metadata sha256 :plugin-api:check`, independently
verify changed artifacts against their upstream publications, and review the metadata diff.
Include platform-specific desktop artifacts when updating them. Release jobs must use the
reviewed third-party hashes and must never generate or accept new ones during publication.
The standalone verifier copies this policy into its temporary project and exempts only the
exact compile-only SDK coordinates being tested; its plugins and other dependencies remain
subject to strict verification.

The [Dependabot configuration](../../.github/dependabot.yml) requests weekly action-update PRs
targeting `kmp`. GitHub reads this configuration from the repository's default branch, so the
maintainer must also install it there to activate updates. Review upstream release changes and
retain full commit SHA pins when merging those PRs.

The [standalone verifier](../../scripts/verify-plugin-sdk.sh) copies only the example and
Gradle wrapper outside the checkout and uses fresh Gradle and Maven local directories.
Run it with `SOUZ_PLUGIN_MAVEN_URL` unset to check a public release. For pre-release verification,
run `./gradlew :plugin-api:publishSdkPublicationToVerificationRepository` and set
`SOUZ_PLUGIN_MAVEN_URL` to the file URI of `plugin-api/build/maven-repository` before running it.
The override resolves only the SDK from that repository and cannot fall back to Maven Central
for a missing staged artifact.

## Invocation services

Use the host-supplied owner and invocation metadata. Arguments cannot select the storage owner
or OAuth user. [Service implementation and limits](../../backend/src/main/kotlin/ru/souz/backend/plugins/PluginServices.kt)

- `state` stores bounded JSON shared across conversations and versions for one user/plugin. Keep
  input and state formats compatible across coexisting versions. Publish a new capability ID
  for breaking behavior. Selecting an earlier version leaves stored state intact.
- `http` supports HTTP(S) without inherited credentials, redirects, shared cookies or automatic
  retries. Validate destinations. `oauth` uses host-owned tokens and enforces provider scopes
  and HTTPS host restrictions. Handle `PluginReconnectRequired` for consent.
- `files` uses owner-scoped stored-file IDs and existing size and expiry rules without resolving
  a sandbox. A false result, exception or cancellation from `channels.send` can mean uncertain
  delivery. Persist business intent before sending and reconcile uncertain effects before retrying.
- `log` and `PluginException` messages must be safe to expose. Omit credentials, arguments, HTTP
  bodies and stored user data. Reserve `TECHNICAL` for failures such as provider outages.

On cancellation or lease failure, stop work and propagate the failure. Use durable reservations
for external effects that could otherwise repeat after an uncertain outcome.
[Versions and pins](../../backend/docs/jvm-plugins.md#versions-and-durable-pins) ·
[Invocation and recovery](../../backend/docs/jvm-plugins.md#invocation-and-recovery)
