# Run the agent evaluation

From the repository root, substitute the credentials, endpoint, and model for your OpenAI-compatible provider.

```bash
SOUZ_AGENT_INTEGRATION_TESTS_ON=true \
SOUZ_AGENT_INTEGRATION_TEST_PROVIDER=openai \
OPENAI_API_KEY='<api-key>' \
OPENAI_BASE_URL='<base-url>' \
OPENAI_MODEL='<model-id>' \
./gradlew :backend:test --tests 'ru.souz.agent.scenarios.AgentScenarioEvaluationTest' --rerun --info
```

Look for `Agent evaluation HTML` and `Agent evaluation JSONL` in standard output for the absolute report paths. After a completed run, both reports are in `backend/build/reports/agent-evaluation/` by default. The JUnit XML in `backend/build/test-results/test/` also records the exact paths.

See the [scenario evaluation guide](../../../../../../../docs/agent-scenario-tests.md) for configuration and report details.
