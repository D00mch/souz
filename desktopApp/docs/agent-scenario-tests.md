# Agent scenario tests

`GraphAgentToolScenariosIntegrationTest` uses live models with isolated desktop tools. Its runtime clock is fixed at June 1, 2026, 10:00 in Europe/Moscow. Calendar, file, and Excel fixtures reflect their arguments and mutations; the missing-chart-columns case expects an explanation without creating a substitute chart.

Each scenario checks the final model response and assistant history as well as requested facts or resulting state. Read-only calls may repeat; side effects retain exact counts. Excel query and sort scenarios accept any correct read-only solution, including reading cells and presenting the result. The lookup scenario specifically exercises `LOOKUP` after optional discovery.

Execution is limited to 24 model calls and four minutes per scenario. `Scenario metrics` records model and tool calls, wall time, token usage, and tool names separately from outcome assertions. Provider failures recovered before completion do not fail an otherwise correct scenario.

Run deterministic fixture, completion, and credential checks with:

```bash
./gradlew :desktopApp:test --tests 'agent.AgentScenario*Test' --tests 'agent.ScenarioCodexAuthTest'
```

For a live Codex run using an existing local ChatGPT login:

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=true \
SOUZ_AGENT_INTEGRATION_TEST_MODEL=gpt-6.1-sol \
SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT=low \
./gradlew :desktopApp:test --tests agent.GraphAgentToolScenariosIntegrationTest --rerun
```

`gpt-5.5` is also supported. The harness reads `auth.json` from `CODEX_HOME` or `~/.codex`; `SOUZ_AGENT_INTEGRATION_CODEX_AUTH_FILE` selects another cache. It validates expiry and uses only the access token and account ID in memory. It does not copy or refresh the login. Expired credentials require refreshing through Codex. Credential loading occurs only after explicit integration-test opt-in.

Other providers use `SOUZ_AGENT_INTEGRATION_TEST_MODEL` and their existing credential variables. For an OpenAI-compatible endpoint, select `openai-compatible-custom` and set `OPENAI_MODEL`, `OPENAI_BASE_URL`, and `OPENAI_API_KEY`. `SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT` applies to classification and execution requests. `SOUZ_AGENT_INTEGRATION_TEST_AGENT=skills` selects the skills graph; `graph` is the default.

JUnit XML and HTML reports are under `desktopApp/build/test-results/test/` and `desktopApp/build/reports/tests/test/`. Preserve them before another test invocation overwrites them. Record the model, reasoning setting, scenario count, failures, and metrics with each live result; scores from different providers or fixtures are separate measurements.
