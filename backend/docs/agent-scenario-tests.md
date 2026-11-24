# Model scenario tests

Each scenario runs the production Skills graph with the full [production catalogue](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioProductionCatalog.kt) and real Skill discovery. Use fresh state and mock tool side effects, subagent execution, memory mutations and sandbox processes. Unconfigured side effects fail. The selected model handles both Skill selection and conversation and is the only external service contacted. [Scenario wiring](../src/test/kotlin/ru/souz/agent/scenarios/AgentScenarioTestSupport.kt)

Keep prompts consistent across models. `SOUZ_BACKEND_SYSTEM_PROMPT` (or `souz.backend.systemPrompt`) overrides the production system prompt. `SOUZ_BACKEND_POLICY_PROMPT` (or `souz.backend.policyPrompt`) supplies optional policy. See the [trace](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioTrace.kt) for the call budget and recorded metrics.

Scenarios use the backend no-op desktop context and must not instantiate desktop integrations or native model execution.

## Evaluate agent tasks across turns

Use the [evaluation quick start](../src/test/kotlin/ru/souz/agent/scenarios/README.md) to run `AgentScenarioEvaluationTest` against an OpenAI-compatible endpoint. Without `SOUZ_AGENT_INTEGRATION_TESTS_ON=true`, the test skips before reading credentials or creating an HTTP client. The test loads the same [bundled Skill archive](../src/test/resources/skills/scenario-set.zip) for every run, including `skills/<id>/SKILL.md` and supporting files.

On Polza (`OPENAI_BASE_URL=https://polza.ai/api/v1`), set `OPENAI_MODEL` to `qwen/qwen3.8-27b`, `deepseek/deepseek-v4.1-flash-1017`, or `z-ai/glm-4.7` to compare the three models.

Set `SOUZ_SCENARIO_REPEATS` to run multiple trials, `SOUZ_SCENARIO_REPORT_DIR` to change the output directory, and `SOUZ_SCENARIO_REVISION` to record a Git revision. Set `SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT` to a value supported by the provider or omit it for the provider default. Keep the provider parameters fixed when comparing revisions.

Each trial runs `skill-switch` in a fresh conversation with the runtime date in Moscow. Mocked searches and Telegram delivery exercise three tasks.

- Search for five doctors, then rerun the search with a price limit. Verify that the Skill uses the limit, returns exactly two eligible doctors, and sends both links to Telegram when requested.
- Search for direct trains and send the link for the departure after noon.
- Find two shows and send the link for the one that starts at 19:30.

The three tasks share one conversation and run in the listed order in every trial. The outcome verifies that the assistant presents the expected search options and the mock Telegram inbox receives exactly the expected links on the requested turns. Answer checks identify doctors by their unique surnames and accept numeric times or equivalent Russian spoken forms, including hour-only readings and explicit afternoon and evening readings. Each show title must be paired with its expected start time; reordered options and time-first schedules are accepted.

The first conversational request is checked for the seeded back-pain exchange. Each subsequent conversational request is checked for earlier user messages, assistant replies, and tool exchanges in order. The history checks exclude the system message and regenerated host context. The cache comparison counts fully matching messages from the start of adjacent conversational requests and records the first differing role, tool definition equality, and cached input tokens on both calls. When the first difference is a system message, it also records the length of the shared start of its text in characters, without storing the prompt. The server reports cache hits in tokens and can reuse prefixes from earlier requests. Cache values and prefix changes are diagnostic and do not affect the task verdict.

An evaluation attempts every scripted turn and checks every expected outcome. After a recoverable execution error, it uses the latest returned conversation state or keeps the user message with the previously available history. Each turn has its own timeout. External cancellation still stops the run. The reports show passed and total assertions and list each failure. A trial with any failed assertion still fails in JUnit.

Open `backend/build/reports/agent-evaluation/<run-id>.html` after the test. The suite generates the page directly and prints the full path in the Gradle test's **Standard output**. If `SOUZ_SCENARIO_REPORT_DIR` is set, open the report in that directory instead. Expand a trial to see its seeded history, each user turn and model reply, Skill IDs requested through `GetSkillByName` and `RunSkillCommand`, and messages accepted by the Telegram mock. Each turn shows model calls, latency, token counts, and any history or cache comparison with the preceding turn. Failed trials are included.

Each HTML report links to the corresponding JSONL with the run configuration and evidence. The run summary shows input and output totals, cached input from `usage.prompt_tokens_details.cached_tokens`, and per-request averages. Cached input is included in input tokens. When a call omits a token count, the corresponding summary value is unavailable and appears as a dash in HTML. An explicit zero remains zero. Use a report directory outside the build tree to retain runs across clean builds. Keep the same scenario version and archive hash when comparing revisions.

Provider errors, empty answers and incomplete final responses fail the run, including a Skill selection error followed by a successful answer. Use the JUnit verdict for scenario success. Compare models using identical scenarios, Skill resources, tool replies and reasoning settings.
