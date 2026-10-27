# Model scenario tests

`ru.souz.agent.scenarios.AgentScenarioTestSupport` evaluates one selected model against Kotlin scenarios. Each scenario supplies a user prompt, classpath `SKILL.md` resources and mocked tools. Assertions in the scenario determine whether the model performed the requested actions, including tool arguments, order and counts.

The harness constructs the production skills graph through `AgentExecutionKernelFactory`, using real Skill discovery and `RunSkillCommand`. Each execution gets a fresh temporary sandbox, registry, catalog, Knowledge store and history. External memory is disabled. The selected model handles both Skill selection and conversational calls. The production system prompt is shared across models. Model-facing host context uses June 1, 2026 at 10:00 in Europe/Moscow; the harness normalizes the injected timestamp at the API boundary because the host environment has no injectable clock.

## Run a selected model

Set `OPENAI_API_KEY` in the invoking environment, then select a model and OpenAI-compatible endpoint. For example:

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=true \
OPENAI_MODEL=gpt-5.6-terra \
OPENAI_BASE_URL=https://api.openai.com/v1 \
SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT=none \
./gradlew :sharedLogic:test --tests 'ru.souz.agent.scenarios.GraphAgentSkillScenariosIntegrationTest'
```

Replace the model ID and endpoint to evaluate another model. `OPENAI_MODEL`, `OPENAI_BASE_URL` and `OPENAI_API_KEY` are required after opt-in. Reasoning effort is optional; use a value supported by the model and endpoint, or omit it for the provider default. Streaming is disabled. Each scenario allows at most 24 model calls and four minutes, including Skill selection. The context size is 32,000 tokens. The harness does not change provider parameters or retry scenarios to obtain a passing result.

Without `SOUZ_AGENT_INTEGRATION_TESTS_ON=true`, live tests skip before reading credentials or creating an HTTP client. Autonomous harness tests require no credentials:

```bash
./gradlew :sharedLogic:test --tests 'ru.souz.agent.scenarios.AgentScenarioTestSupportTest' \
  --tests 'ru.souz.agent.scenarios.ScenarioLiveModelTest'
```

## Add a scenario

Add an ordinary Kotlin test in the same package. Create mocked `LLMToolSetup` objects with their schemas and replies, then call:

```kotlin
val result = model.testSupport().runScenario(
    userPrompt = "Выполни тестовую процедуру для CASE-42.",
    skillResources = mapOf("scenario-smoke" to "/skills/scenario-smoke/SKILL.md"),
    mockedTools = listOf(tool),
)
```

Use `ScenarioLiveModel.fromEnvironment().use { model -> ... }` inside `runTest(timeout = 5.minutes)` to manage opt-in and client lifetime. `skillResources` maps exact Skill IDs to classpath Markdown files under `src/test/resources`. The harness installs each file as a real bundle; instructions reach the model through discovery. Tool-backed Skills are grouped under `FILES` in the isolated catalog. Mock both the tool's `fn` and its metadata-aware `invoke(functionCall, meta)` method.

Verify the target mock with MockK and inspect `result.events` for ordering. `result.execution`, `result.history` and `result.requests` expose the runtime result and actual model-facing requests. `result.metrics` contains the configured model and reasoning effort, model-call count, token usage, elapsed time and ordered tool calls. A completed assistant answer alone does not establish scenario success: the scenario must assert the required tool behavior. Read-only discovery calls may repeat; enforce exact counts on the intended side effects.

`GraphAgentSkillScenariosIntegrationTest` is the single technical example. Its `scenario-smoke/SKILL.md` supplies a control value absent from the user prompt and tool schema; the test checks successful Skill reading followed by exactly one target invocation with the required arguments.

## Read results

The harness rejects provider errors, empty answers and incomplete final responses. It prints execution completion status and metrics in `finally`, including when a request fails or reaches its deadline. Scenario assertions run after execution and determine JUnit success or failure; `execution=completed` describes the agent run, not the scenario verdict.

JUnit XML and HTML reports are under `sharedLogic/build/test-results/test/` and `sharedLogic/build/reports/tests/test/`. They include stdout with model metadata and the tool sequence. Preserve reports before rerunning with another model, because Gradle overwrites them. Compare models using identical scenarios, Skill resources, tool replies and reasoning settings. A failed scenario is an evaluation result; do not relax its expectations to make a model pass.
