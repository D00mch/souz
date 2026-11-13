# Shared Logic

`:sharedLogic` provides the backend's Kotlin/JVM provider clients, classifiers, Skill runtime, sandboxes, and tools. See [sandbox and Skill storage](docs/runtime-sandbox-and-skills.md) and [tool catalog composition](docs/tool-catalog-composition.md).

## Jev classification

Export `JEV_TOKEN` in the environment that starts Souz, then run `./gradlew :backend:run`.

- `JEV_MODEL` selects the hosted model. See [Jev client configuration](src/main/kotlin/ru/souz/jev/JevClient.kt).
- `JEV_THRESHOLD` controls Skill selection. See [classifier wiring](src/main/kotlin/ru/souz/runtime/di/RuntimeSharedDiModules.kt) and [Jev classification and prompts](src/main/kotlin/ru/souz/llms/runtime/JevClassifier.kt).
- Set `FRIDA_URL` to the vLLM `/pooling` endpoint to enable the Skill-selection fallback, with optional `FRIDA_TOKEN` and `FRIDA_MODEL`. See [FRIDA client configuration](src/main/kotlin/ru/souz/frida/FridaClient.kt) and [FRIDA classification](src/main/kotlin/ru/souz/llms/runtime/FridaClassifier.kt).
- `JEV_WAIT_CATEGORY_THRESHOLD` controls confidence for a specific waiting message. See [backend configuration](../backend/src/main/kotlin/ru/souz/backend/app/BackendAppConfig.kt) and [model routing](../agent/docs/execution-lifecycle.md#model-routing).

See [Skill inventory and approval](../agent/docs/skill-activation.md) for classifier fallback. See [provider HTTP lifecycle](docs/provider-http-lifecycle.md) for transport ownership and request-local credentials, and [HTTP client profiles](src/main/kotlin/ru/souz/llms/http/ProviderHttpClients.kt) for transport settings.

Inject `JevClient` to evaluate a named question directly.

```kotlin
import com.fasterxml.jackson.databind.JsonNode
import ru.souz.jev.JevClient
import ru.souz.llms.restJsonMapper

suspend fun needsCalendar(jev: JevClient, message: String): Double = jev.evaluate(
    state = restJsonMapper.valueToTree<JsonNode>(message),
    questions = mapOf("calendar" to "Does this request require calendar access?"),
).getValue("calendar")
```

Run the opt-in hosted Jev test with `JEV_TOKEN` exported.

```zsh
SOUZ_TEST_JEV=1 ./gradlew :sharedLogic:test --tests 'ru.souz.jev.JevIntegrationTest' --rerun-tasks
```

## Sandbox modes

Run from the repository root with `SOUZ_SANDBOX_MODE` unset for local mode.

```zsh
unset SOUZ_SANDBOX_MODE
./gradlew :backend:run
```

For Docker mode, start Docker and build the sandbox image before launching the backend.

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_SANDBOX_MODE=docker ./gradlew :backend:run
```

The [Dockerfile](Dockerfile) defines the image contents. The [entrypoint](docker/entrypoint.sh) seeds bundled Skills into the mounted registry on startup.

## Tests

```zsh
./gradlew :sharedLogic:test
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*'
```

The Docker suite is opt-in. See [sandbox verification](docs/runtime-sandbox-and-skills.md#verification) for focused runtime checks.

## Troubleshooting

- If `souz-runtime-sandbox:latest` is unavailable, run `./gradlew :sharedLogic:buildRuntimeSandboxImage`.
- If Docker mode starts unexpectedly, check your shell profile, IDE run configuration, or launch script for `SOUZ_SANDBOX_MODE=docker`.
- If Gradle cannot find Docker, run `SOUZ_DOCKER_CLI=/path/to/docker ./gradlew :sharedLogic:buildRuntimeSandboxImage`.
- If the bundled `paper-summarize-academic` Skill is missing, start the sandbox to seed it. To reseed an existing copy, back up any edits, remove its `state/skills/paper-summarize-academic` directory inside the sandbox's host root, and restart the container. The entrypoint preserves existing files. See [host root selection](src/main/kotlin/ru/souz/runtime/sandbox/RuntimeSandboxFactory.kt).
