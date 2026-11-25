# Backend agent scenario evaluation

Run the [seven-turn evaluation](../src/test/kotlin/ru/souz/agent/scenarios/AgentScenarioEvaluationTest.kt) from the repository root with `OPENAI_BASE_URL`, `OPENAI_MODEL`, and `OPENAI_API_KEY` set for your OpenAI-compatible provider.

```sh
SOUZ_AGENT_INTEGRATION_TESTS_ON=true SOUZ_SCENARIO_REVISION="$(git rev-parse HEAD)" \
  ./gradlew :backend:test --tests 'ru.souz.agent.scenarios.AgentScenarioEvaluationTest' --rerun
```

Ordinary backend tests skip the evaluation before opening a client. Set `SOUZ_SCENARIO_REPEATS` for independent repetitions and `SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT` for a provider-supported effort. Keep the model, provider, reasoning effort, prompt overrides, scenario version, and Skill archive hash with each comparison.

## What a trial measures

Each trial opens a fresh conversation with the production Skills graph, the [backend catalogue](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioProductionCatalog.kt), the backend no-op desktop context, and the same [31 bundled Skills](../src/test/resources/skills/scenario-set.zip). The selected model handles Skill discovery and conversation. A sandbox command stub returns search results, the production channel tools deliver to an in-memory Telegram inbox, and unconfigured side effects fail. The fixture and agent share the Moscow clock captured at suite start. The production system and policy prompt overrides apply.

| Turns | User request | Scored outcome |
| --- | --- | --- |
| 1–3 | Find five Moscow therapists for next Wednesday, narrow the search to ₽2,500, then send both links. | The model presents the five surnames, runs a new capped search, presents only the two eligible doctors, and delivers each link once. |
| 4–5 | Show two direct trains and send the afternoon departure. | The reply gives both departure times in digits and Telegram receives the later train’s link. |
| 6–7 | Show two plays and send the one starting at 19:30. | Each requested title appears with its numeric start time on its own line and Telegram receives the matching link. |

Each search must return the expected options through its bundled Skill command. Each delivery must occur on the requested turn. The doctor links can arrive together or in separate messages. The [fixture](../src/test/kotlin/ru/souz/agent/scenarios/ScenarioFixture.kt) defines the command arguments, results, and acceptance checks.

## Reports

Open the matching HTML and JSONL files under `backend/build/reports/agent-evaluation/` or set `SOUZ_SCENARIO_REPORT_DIR` to another directory. Every run gets a unique ID. Reports include assertion scores, failures, transcripts, selected tools and Skills, Telegram messages, and model call and suite timing. Token totals and averages use provider-reported usage. Missing measurements stay unavailable and reported zeroes stay zero.

The first conversational request must contain the seeded exchange. Across turns the evaluator checks the order of earlier user, assistant, and tool messages. It also compares the wire message prefix and tool definitions and records cached input on both calls. Changes to system text and cache measurements are diagnostic.

Each turn has a four-minute timeout and a budget of 24 model calls. After a recoverable turn failure the trial continues from the latest available history. Provider errors, execution errors, and failed checks have separate verdicts. Any unsuccessful trial fails JUnit after the reports are written.
