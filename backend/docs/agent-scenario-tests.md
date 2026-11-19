# Model scenario tests

Each scenario runs the production Skills graph with the full [production catalogue](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioProductionCatalog.kt) and real Skill discovery. Use fresh state and mock tool side effects, subagent execution, memory mutations and sandbox processes. Unconfigured side effects fail. The selected model handles both Skill selection and conversation and is the only external service contacted. [Scenario wiring](../src/test/kotlin/ru/souz/agent/scenarios/AgentScenarioTestSupport.kt)

Keep prompts consistent across models. `SOUZ_BACKEND_SYSTEM_PROMPT` (or `souz.backend.systemPrompt`) overrides the production system prompt. `SOUZ_BACKEND_POLICY_PROMPT` (or `souz.backend.policyPrompt`) supplies optional policy. See the [trace](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioTrace.kt) for the call budget and recorded metrics.

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

## Evaluate agent tasks across turns

Run [AgentScenarioEvaluationTest](../src/test/kotlin/ru/souz/agent/scenarios/AgentScenarioEvaluationTest.kt) against an OpenAI-compatible endpoint. The test loads the same [bundled Skill archive](../src/test/resources/skills/scenario-set.zip) for every run. The archive contains `skills/<id>/SKILL.md` and supporting files.

```bash
export SOUZ_AGENT_INTEGRATION_TESTS_ON=true
export SOUZ_SCENARIO_REPORT_DIR=/absolute/path/to/evaluation-runs
export SOUZ_SCENARIO_REVISION="$(git rev-parse HEAD)"
export OPENAI_BASE_URL=https://polza.ai/api/v1
export OPENAI_MODEL=qwen/qwen3.8-27b
# Set OPENAI_API_KEY through the invoking environment.
./gradlew :backend:test --rerun --tests 'ru.souz.agent.scenarios.AgentScenarioEvaluationTest'
```

Set `OPENAI_MODEL` to `deepseek/deepseek-v4.1-flash-1017` or `z-ai/glm-4.7` to compare those models with Qwen. All three use the same OpenAI-compatible adapter and read cache hits from `usage.prompt_tokens_details.cached_tokens`. `SOUZ_SCENARIO_REPEATS` defaults to 1. Each trial runs `skill-switch` in a fresh conversation, using the Skills from the archive and the unmodified runtime date in Moscow. Mocked searches and Telegram delivery exercise three tasks:

- Search for five doctors, then rerun the search with a price limit. Verify that the Skill uses the limit, returns exactly two eligible doctors, and sends both links to Telegram when requested.
- Search for direct trains and send the link for the departure after noon.
- Find two shows and send the link for the one that starts at 19:30.

The three tasks share one conversation and run in the listed order in every trial. The outcome verifies that the mock Telegram inbox receives exactly the expected links on the requested turns.

Each turn checks that the next conversational request preserves earlier user messages, assistant replies, and tool exchanges in order. It excludes the system message and regenerated host context from that history assertion. The cache comparison counts fully matching messages from the start of adjacent conversational requests and records the first differing role, tool definition equality, and cached input tokens on both calls. When the first difference is a system message, it also records the length of the shared start of its text in characters, without storing the prompt. The server reports cache hits in tokens and can reuse prefixes from earlier requests. Cache values and prefix changes are diagnostic and do not affect the task verdict.

An evaluation attempts every scripted turn and checks every expected outcome. After a recoverable execution error, it uses the latest returned conversation state or keeps the user message with the previously available history. Each turn has its own timeout. External cancellation still stops the run. The reports show passed and total assertions and list each failure. A trial with any failed assertion still fails in JUnit.

Open `backend/build/reports/agent-evaluation/<run-id>.html` after the test. The suite generates the page directly and prints the full path in the Gradle test's **Standard output**. If `SOUZ_SCENARIO_REPORT_DIR` is set, open the report in that directory instead. Expand a trial to see its seeded history, each user turn and model reply, Skill IDs requested through `GetSkillByName` and `RunSkillCommand`, and messages accepted by the Telegram mock. Each turn shows model calls, latency, token counts, and any history or cache comparison with the preceding turn. Failed trials are included.

Each HTML report links to the corresponding JSONL with the run configuration and evidence. Cached input is included in input tokens. An unavailable value appears as a dash in HTML and an explicit zero remains zero. Use a report directory outside the build tree to retain runs across clean builds. Keep the same scenario version and archive hash when comparing revisions.

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
