# Model scenario tests

Each scenario runs the production Skills graph with the full [production catalogue](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioProductionCatalog.kt) and real Skill discovery. Use fresh state and mock tool side effects, subagent execution, memory mutations and sandbox processes. Unconfigured side effects fail. The selected model handles both Skill selection and conversation and is the only external service contacted. [Scenario wiring](../src/test/kotlin/ru/souz/agent/scenarios/AgentScenarioTestSupport.kt)

Keep prompts consistent across models. `SOUZ_BACKEND_SYSTEM_PROMPT` (or `souz.backend.systemPrompt`) overrides the production system prompt. `SOUZ_BACKEND_POLICY_PROMPT` (or `souz.backend.policyPrompt`) supplies optional policy. See the [trace](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioTrace.kt) for the fixed model-facing date, call budget, and recorded metrics.

Use the fixed runtime-environment clock for deterministic host context. Scenarios use the backend no-op desktop context and must not instantiate desktop integrations or native model execution.

## Run a selected model

Set `OPENAI_API_KEY` in the invoking environment, then select a model and OpenAI-compatible endpoint. For example:

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=true \
OPENAI_MODEL=gpt-5.6-terra \
OPENAI_BASE_URL=https://api.openai.com/v1 \
SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT=none \
./gradlew :backend:test --tests 'ru.souz.agent.scenarios.GraphAgentSkillScenariosIntegrationTest'
```

Replace the model ID and endpoint to evaluate another model. The provider defaults to `openai`; `OPENAI_MODEL`, `OPENAI_BASE_URL` and `OPENAI_API_KEY` are required after opt-in.

For an existing Codex login, set `SOUZ_AGENT_INTEGRATION_TEST_PROVIDER=codex`, select the Codex model with `OPENAI_MODEL`, and supply its current `CODEX_ACCESS_TOKEN` and `CODEX_ACCOUNT_ID` through the invoking process environment. This uses the production `CodexChatAPI`; `OPENAI_API_KEY` and `OPENAI_BASE_URL` are not required. The harness borrows only the access token. Its owning Codex session manages refresh; obtain a current token again if it expires. Keep tokens out of tracked files and test output.

Reasoning effort is optional. Use a value supported by the selected provider or omit it for the provider default. Keep the configured provider parameters and report the original scenario result without retries.

Without `SOUZ_AGENT_INTEGRATION_TESTS_ON=true`, live tests skip before reading credentials or creating an HTTP client.

## Add a scenario

Add an ordinary Kotlin test in the same package. Create mocked `LLMToolSetup` objects with their schemas and replies, then call:

```kotlin
val result = model.runScenario(
    userPrompt = "Запиши результат проверки заявки CASE-42 в журнал.",
    skillResources = mapOf("scenario-smoke" to "/skills/scenario-smoke/SKILL.md"),
    mockedTools = listOf(tool),
)
```

Use `ScenarioLiveModel.fromEnvironment().use { model -> ... }` inside `runTest(timeout = 5.minutes)` for opt-in and client cleanup. Map exact Skill IDs to classpath Markdown and let normal discovery select them. A mock matching a production tool name replaces execution and preserves production metadata. Mock both `fn` and `invoke(functionCall, meta)`. Use natural requests that leave the Skill or command for the model to discover.

Assert tool arguments, ordering and exact side-effect counts. Read-only discovery may repeat. The [example scenario](../src/test/kotlin/ru/souz/agent/scenarios/GraphAgentSkillScenariosIntegrationTest.kt) checks discovery followed by one invocation with a control value found only in the Skill.

## Read results

Provider errors, empty answers and incomplete final responses fail the run, including a Skill selection error followed by a successful answer. Use the JUnit verdict for scenario success. The harness prints execution status and metrics even on failure.

JUnit XML and HTML reports are under `backend/build/test-results/test/` and `backend/build/reports/tests/test/`. They include stdout with model metadata and the tool sequence. Preserve reports before rerunning with another model, because Gradle overwrites them. Compare models using identical scenarios, Skill resources, tool replies and reasoning settings. A failed scenario is an evaluation result; do not relax its expectations to make a model pass.
