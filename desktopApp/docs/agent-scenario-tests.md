# Agent scenario tests

`GraphAgentToolScenariosIntegrationTest` contains 94 live-model cases with isolated desktop tools. It needs Java 21 and the Gradle wrapper, but no running desktop app, backend, Orion, Docker, or graphical desktop session. Its runtime clock is fixed at June 1, 2026, 10:00 in Europe/Moscow. Calendar, file, and Excel fixtures reflect their arguments and mutations; the missing-chart-columns case expects an explanation without creating a substitute chart.

Every scenario receives the full production compiled-tool catalog and the same 30 file-backed Skills from the [2026-10-29 export](https://disk.yandex.ru/d/2r6zoI_x33TFaA). The [fixture index](../src/test/resources/agent/skills/index.json) pins one variant per ID: most deployments, then lexicographically smallest full SHA-256 on a tie. All 108 selected source files are checksum-verified. User identities and deployments are omitted; local installed Skills and MCP tools are not loaded.

Scenario-specific mocks implement operations and state changes. Other compiled tools remain discoverable and callable but return `scenario_tool_not_configured`. File-backed Skill detail exposes the pinned instructions and supporting-file names; commands return a simulated exit code 1 with `scenario_skill_not_configured`. No bundled script or external service is executed. The test approval cache accepts only pinned bundle hashes and makes no live validation calls. See the [fixture README](../src/test/resources/agent/skills/README.md) for refresh rules.

The normal graph exposure rules remain: `skills` installs fixed core functions and uses the full Skill inventory; `graph` classifies direct tools. Category discovery lists compiled tools; file-backed Skills load by exact inventory ID. Full availability does not inject every schema into each request. Results from the earlier per-scenario-only catalog are separate measurements and must retain their fixture commit.

Each scenario checks the final model response and assistant history as well as requested facts or resulting state. Scenarios declaring mocked tools require a completed tool call; text-only prompt evaluations accept a completed textual answer. Read-only calls may repeat; side effects retain exact counts. Excel query and sort scenarios accept any correct read-only solution, including reading cells and presenting the result. The lookup scenario specifically exercises `LOOKUP` after optional discovery.

Execution is limited to 24 model calls and four minutes per scenario, with a 60-second HTTP timeout, 32,000-token context, and streaming disabled. `Scenario metrics` records model and tool calls, wall time, token usage, and tool names separately from outcome assertions. Provider failures recovered before completion do not fail an otherwise correct scenario. Test error messages do not load Compose resources, so a model timeout is not masked by `HeadlessException`.

Run deterministic fixture, completion, and credential checks with:

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=false ./gradlew :desktopApp:test --tests 'agent.AgentScenario*Test' --tests 'agent.ScenarioCodexAuthTest'
```

For a live Codex run using an existing local ChatGPT login:

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=true \
SOUZ_AGENT_INTEGRATION_TEST_MODEL=gpt-6.1-sol \
SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT=low \
./gradlew :desktopApp:test --tests agent.GraphAgentToolScenariosIntegrationTest --rerun
```

`gpt-5.5` is also supported. The harness reads `auth.json` from `CODEX_HOME` or `~/.codex`; `SOUZ_AGENT_INTEGRATION_CODEX_AUTH_FILE` selects another cache. It validates expiry and keeps the access token, account ID, and expiry in memory. The provider checks that expiry before every request, including later scenarios. The harness does not copy or refresh the login. Expired credentials require refreshing through Codex. Credential loading occurs only after explicit integration-test opt-in.

Other providers use `SOUZ_AGENT_INTEGRATION_TEST_MODEL` and their existing credential variables. For an OpenAI-compatible endpoint, select `openai-compatible-custom` and set `OPENAI_MODEL`, `OPENAI_BASE_URL`, and `OPENAI_API_KEY`. `SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT` applies to classification and execution requests. `SOUZ_AGENT_INTEGRATION_TEST_AGENT=skills` selects the skills graph; `graph` is the default.

JUnit XML and HTML reports are under `desktopApp/build/test-results/test/` and `desktopApp/build/reports/tests/test/`. Preserve them before another test invocation overwrites them. Record the model, reasoning setting, scenario count, failures, and metrics with each live result; scores from different providers or fixtures are separate measurements.

For provider availability checks, AITunnel configuration, isolated full-run reports, and result recording, use [the live evaluation runbook](../../docs/experiments/live-agent-scenarios.md). It includes the shared result spreadsheet and prevents stale reports from being mistaken for a new score. Confirm an exact model in its authenticated provider catalog before starting; never substitute a model silently.

For DeepSeek connection settings, rerun commands, and the recorded evaluation, see [the DeepSeek scenario guide](../../docs/experiments/deepseek-skills-2026-10-26.md).
The [Qwen3.7 Flash/AITunnel measurement](../../docs/experiments/qwen37-flash-aitunnel-2026-11-01.md) records the pre-expansion catalog and its provider failures.
