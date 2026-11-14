# Shared Logic

`:sharedLogic` provides the Kotlin Multiplatform provider clients, classifiers, Skill runtime, sandboxes, and tools shared by desktop and backend JVM hosts. See [source-set and host boundaries](AGENTS.md#source-set-boundaries). See [sandbox and Skill storage](docs/runtime-sandbox-and-skills.md) and [tool catalog composition](docs/tool-catalog-composition.md).

## Jev classification

Export `JEV_TOKEN` in the environment that starts Souz. For desktop category and Skill selection, set `SOUZ_CLASSIFIER=jev` and run `./gradlew :desktopApp:run`. Backend Skills use the same classifier independently of desktop settings.

- `JEV_MODEL` selects the hosted model. See [Jev client configuration](src/commonJvmMain/kotlin/ru/souz/jev/JevClient.kt).
- `JEV_THRESHOLD` controls category and Skill selection. See [classifier wiring](src/jvmMain/kotlin/ru/souz/runtime/di/RuntimeSharedDiModules.kt) and [Jev classification and prompts](src/commonJvmMain/kotlin/ru/souz/llms/runtime/JevClassifier.kt).
- Set `FRIDA_URL` to the vLLM `/pooling` endpoint to enable category and Skill-selection fallback, with optional `FRIDA_TOKEN` and `FRIDA_MODEL`. See [FRIDA client configuration](src/commonJvmMain/kotlin/ru/souz/frida/FridaClient.kt) and [FRIDA classification](src/commonJvmMain/kotlin/ru/souz/llms/runtime/FridaClassifier.kt).
- `JEV_WAIT_CATEGORY_THRESHOLD` controls confidence for a specific waiting message. See [backend configuration](../backend/src/main/kotlin/ru/souz/backend/app/BackendAppConfig.kt) and [model routing](../agent/docs/execution-lifecycle.md#model-routing).

See [Skill inventory and approval](../agent/docs/skill-activation.md) for classifier fallback. See [provider HTTP lifecycle](docs/provider-http-lifecycle.md) for transport ownership and request-local credentials, and [HTTP client profiles](src/commonJvmMain/kotlin/ru/souz/llms/http/ProviderHttpClients.kt) for transport settings.

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
SOUZ_TEST_JEV=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.jev.JevIntegrationTest' --rerun-tasks
```

Category selection evaluates enabled, nonempty categories only; no matches or `HELP` retain all available tools. Jev category failures get two attempts before FRIDA and regex fallback. Skill selection tries Jev, then FRIDA on failure, then the execution LLM. A valid empty selection is not a failure. Confirmations remain classified. Classifier dialogue contains user and assistant messages with tool names only, excluding system messages, tool arguments and results. [Classification contracts](../agent/docs/execution-lifecycle.md#safe-changes)

## Sandbox modes

Run from the repository root with `SOUZ_SANDBOX_MODE` unset for local mode.

```zsh
unset SOUZ_SANDBOX_MODE
./gradlew :backend:run
```

For Docker mode, start Docker and build the sandbox image before launching either JVM host. Use `:desktopApp:run` in place of `:backend:run` for desktop.

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_SANDBOX_MODE=docker ./gradlew :backend:run
```

The [Dockerfile](Dockerfile) defines the image contents. The [entrypoint](docker/entrypoint.sh) seeds bundled Skills into the mounted registry on startup.

## Tests

```zsh
./gradlew :sharedLogic:jvmTest
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
```

The Docker suite is opt-in. See [sandbox verification](docs/runtime-sandbox-and-skills.md#verification) for focused runtime checks.

## Troubleshooting

- If `souz-runtime-sandbox:latest` is unavailable, run `./gradlew :sharedLogic:buildRuntimeSandboxImage`.
- If Docker mode starts unexpectedly, check your shell profile, IDE run configuration, or launch script for `SOUZ_SANDBOX_MODE=docker`.
- If Gradle cannot find Docker, run `SOUZ_DOCKER_CLI=/path/to/docker ./gradlew :sharedLogic:buildRuntimeSandboxImage`.
- If the bundled `paper-summarize-academic` Skill is missing, start the sandbox to seed it. To reseed an existing copy, back up any edits, remove its `state/skills/paper-summarize-academic` directory inside the sandbox's host root, and restart the container. The entrypoint preserves existing files. See [host root selection](src/jvmMain/kotlin/ru/souz/runtime/sandbox/RuntimeSandboxFactory.kt).
