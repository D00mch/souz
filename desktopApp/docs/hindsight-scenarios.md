# Hindsight conversation scenarios

`HindsightConversationScenariosIntegrationTest` runs fixed conversations through the real graph agent and backend Hindsight adapter. By default, `LLMChatAPI` returns scripted answers and tool calls; no agent provider client or key is needed. External tools have fixed fixture responses. The suite requires a separately running Hindsight server with its own extraction/embedding models configured. Those models remain real and can incur costs.

## Prepare Hindsight

Use Java 21 and run Gradle commands from the repository root. The Souz backend does not need to be running: the test invokes its memory adapter directly.

For an existing test server, set `HINDSIGHT_API_URL` and, if it requires authentication, `HINDSIGHT_API_TOKEN`. Otherwise, unset `HINDSIGHT_API_TOKEN`.

For a local server, configure `OPENAI_API_KEY` and a separate `HINDSIGHT_API_TOKEN` in your shell, then start this pinned Docker image. The OpenAI key is used by Hindsight for extraction; the service token authenticates the test client. Keep the service token for later sessions.

```bash
: "${OPENAI_API_KEY:?Set the Hindsight extraction provider key}"
: "${HINDSIGHT_API_TOKEN:?Set a local Hindsight service token}"
export HINDSIGHT_API_TOKEN
export HINDSIGHT_API_LLM_API_KEY="$OPENAI_API_KEY"
export HINDSIGHT_API_TENANT_API_KEY="$HINDSIGHT_API_TOKEN"
export HINDSIGHT_CP_DATAPLANE_API_KEY="$HINDSIGHT_API_TOKEN"

docker run -d --name souz-hindsight-tests --shm-size=1g \
  -p 127.0.0.1:8888:8888 -p 127.0.0.1:9999:9999 \
  -v souz-hindsight-tests-data:/home/hindsight/.pg0 \
  -e HINDSIGHT_API_LLM_PROVIDER=openai \
  -e HINDSIGHT_API_LLM_MODEL=gpt-4o-mini \
  -e HINDSIGHT_API_LLM_API_KEY \
  -e HINDSIGHT_API_STORE_DOCUMENT_TEXT=true \
  -e HINDSIGHT_API_EMBEDDINGS_LOCAL_MODEL=BAAI/bge-m3 \
  -e HINDSIGHT_API_RERANKER_LOCAL_MODEL=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 \
  -e HINDSIGHT_API_TENANT_EXTENSION=hindsight_api.extensions.builtin.tenant:ApiKeyTenantExtension \
  -e HINDSIGHT_API_TENANT_API_KEY \
  -e HINDSIGHT_CP_DATAPLANE_API_URL=http://localhost:8888 \
  -e HINDSIGHT_CP_DATAPLANE_API_KEY \
  ghcr.io/vectorize-io/hindsight:0.9.2

export HINDSIGHT_API_URL=http://localhost:8888
curl --fail --silent --show-error "$HINDSIGHT_API_URL/health"
```

Wait for the health check to succeed before running tests; first startup downloads the local embedding and reranker models. Inspect startup with `docker logs --tail 100 souz-hindsight-tests`. Stop the server with `docker stop souz-hindsight-tests`, and resume the same container with `docker start souz-hindsight-tests`. The named volume preserves the banks; replay requires those data and the same service token.

## 1. Create a corpus and check memory

Start with `scripted`: user messages, agent answers and tool calls are fixed. Only Hindsight calls real models. Set the URL to your server:

```bash
export HINDSIGHT_API_URL=http://localhost:8888
export SOUZ_HINDSIGHT_SCENARIOS_ON=true
export SOUZ_HINDSIGHT_AGENT_LLM=scripted
export SOUZ_AGENT_INTEGRATION_TEST_AGENT=skills
unset SOUZ_HINDSIGHT_EVAL_MANIFEST

./gradlew :desktopApp:test --tests 'agent.HindsightConversationScenariosIntegrationTest'
```

The separate opt-in flag leaves ordinary tool scenarios disabled. `SOUZ_AGENT_INTEGRATION_TEST_AGENT=skills|graph` selects the agent; this suite defaults to `skills`. A run has a 30-minute timeout. Gradle does not reuse cached test results while this flag is enabled.

`SOUZ_HINDSIGHT_AGENT_LLM=scripted|live` selects the agent's LLM behavior for both seeding and probes, defaulting to `scripted`. For real agent answers, set it to `live` and configure the provider's key (`ANTHROPIC_API_KEY` for the default model). `SOUZ_AGENT_INTEGRATION_TEST_MODEL` accepts the same model name/alias as other agent integration scenarios. In `scripted`, that model is routing metadata, not a model being called. In `live`, calls consume provider tokens.

Each episode starts with empty short-term history; turns within it carry the previous agent context forward. Episodes can share a chat. New corpus owners use unique `souz-memory-test-...` bank IDs; banks are preserved after success or failure.

## 2. Inspect results

The test writes artifacts under `desktopApp/build/reports/hindsight/<runId>/`. Start with `report.md`; the run directory is also printed in the test's standard output, available in Gradle's HTML report at `desktopApp/build/reports/tests/test/index.html`.

- `manifest.json`: corpus identities, source documents, seed settings including `agentLlm`, scenario definitions and recorded turns. `complete` becomes true only after every seed turn is verified.
- `transcript.json`: user/assistant messages and tool calls/results.
- `results.json`: each probe's answer, injected/search-tool memory, used fact IDs, source documents, HTTP retrieval results, and independent checks. Scripted runs also record `llmRequestMemoryContext`, the memory actually delivered to the LLM boundary.
- `hindsight-http.json`: server responses without request headers or credentials. Failed responses retain only status/type.
- `evaluation.json`, `report.md`: evaluation settings and results. `failure.json` records infrastructure failure type when applicable.

Required checks cover remembered details and owner isolation. Diagnostic checks retain desired outcomes for latest-discussion selection, cross-chat retrieval, assistant-only recommendations and abstention. Diagnostic failures are reported without failing JUnit. All modes fail on infrastructure errors.

In `scripted`, every probe gets a neutral fixed answer. Source and context checks run, including checks against the memory reaching `LLMChatAPI`. Answer-quality checks are `SKIPPED` with reason `фиксированный ответ агента`; their desired outcomes remain available in `live`. Film recommendations must appear in memory, and owner isolation checks exclude the other user's values from memory as well as sources. Abstention cannot be evaluated with a fixed answer.

Individual checks are `PASS`, `FAIL` or `SKIPPED`. A probe is `PARTIAL` when its executed checks pass but answer checks are skipped, and `SKIPPED` when no quality checks ran. Neither is reported as a full success. Required `FAIL` results fail JUnit; skips remain visible without failing the run.

Checks use source IDs and explicit text patterns, not full-answer equality or an LLM judge. A matching title can be a guess; inspect its sources and memory context. Regex checks do not establish semantic correctness. `unresolvedSourceFactIds` identifies results with unavailable provenance: the current production recall request does not request observation source facts. An empty resolved-document list alone does not prove that no memory was used. Every probe also checks that all HTTP requests targeted its expected owner bank.

## 3. Reuse a corpus

Keep the same Hindsight server and point to a completed manifest; seeding is skipped:

```bash
export SOUZ_HINDSIGHT_EVAL_MANIFEST=/absolute/path/to/manifest.json
./gradlew :desktopApp:test --tests 'agent.HindsightConversationScenariosIntegrationTest'
```

Unset `SOUZ_HINDSIGHT_EVAL_MANIFEST` to create a new corpus. Replay uses the scenarios embedded in the manifest, even if the checked-in fixture changed. The current model/agent/LLM-mode selection controls evaluation; original seed settings remain in the manifest. Manifests without `agentLlm` describe a `live` seed and can be evaluated in either mode without scripted seed answers. No endpoint or token is stored in the manifest.

Every probe starts with empty history. Capture is disabled, so evaluations do not teach Hindsight their own questions/answers. Document existence is checked before replay; missing documents or API failures fail the run rather than masquerading as retrieval misses. Background Hindsight consolidation can still change retrieval over time; a preserved bank is not a frozen database snapshot.

## 4. Check real agent answers

Keep `SOUZ_HINDSIGHT_EVAL_MANIFEST` pointing to the completed corpus, configure `ANTHROPIC_API_KEY` for the default agent model, and switch to `live`:

```bash
: "${SOUZ_HINDSIGHT_EVAL_MANIFEST:?Select a completed manifest first}"
: "${ANTHROPIC_API_KEY:?Set the agent provider key}"
export ANTHROPIC_API_KEY
export SOUZ_HINDSIGHT_AGENT_LLM=live
./gradlew :desktopApp:test --tests 'agent.HindsightConversationScenariosIntegrationTest'
```

This evaluates real answers against the existing memory and consumes agent provider tokens. To compare agents against the same corpus, set `SOUZ_AGENT_INTEGRATION_TEST_AGENT=graph` or `skills` and rerun. To return to fixed answers, set `SOUZ_HINDSIGHT_AGENT_LLM=scripted`.

## 5. Add conversations and probes

Edit [hindsight-conversations.json](../src/test/resources/agent/hindsight-conversations.json):

- `episodes`: conversations with unique episode and turn `id` values; `owner` and `chat` default to `main`.
- Each turn has user `text`, a fixed `assistant` answer, ordered `toolCalls` with `name` and `arguments`, and `toolResults` keyed by tool name. Scripted calls and result keys must match.
- `probes`: questions asked with empty history, using `contextPatterns` for retrieved memory and `answerPatterns` for live answers. `sourceTurns` refers to source turn IDs; `forbiddenSourceOwner` and `forbiddenAnswerPatterns` check owner isolation. Use `diagnostic: true` with a `limitation` for desired behavior the adapter does not guarantee.

Scripted skills discover each tool with `GetSkillByName`, then use `RunSkillCommand`; the graph agent invokes the tool directly and receives a fixed classification answer. The script rejects unexpected requests, summarization, missing tool results and unused steps; it never falls back to a provider. `live` uses the same user text and tool results, while the model chooses its own calls and answer.

After editing fixtures, unset `SOUZ_HINDSIGHT_EVAL_MANIFEST` and repeat step 1. Replaying an old manifest uses its embedded scenarios, not the edited file.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| JUnit test is skipped | Export `SOUZ_HINDSIGHT_SCENARIOS_ON=true` in the shell running Gradle. |
| Connection refused or empty health response | Start Hindsight and wait for model loading and database initialization. |
| HTTP 401/403 | Set the client `HINDSIGHT_API_TOKEN` to the server's service token; it is not the extraction provider key. |
| Retain or recall fails | Inspect `failure.json`, `hindsight-http.json`, the Gradle test report and server logs; verify the extraction provider configuration. |
| Replay rejects the manifest or cannot find documents | Use a manifest with `complete: true` against its original Hindsight data, or unset the manifest and create a new corpus. |
| `cross-chat` or `assistant-only-film` is `FAIL` | These diagnostic probes expose current capture/scope limits; inspect their context and source checks. |

## Local verification

```bash
./gradlew :desktopApp:test --tests 'agent.HindsightScenarioSupportTest' --tests 'agent.AgentScenarioTestSupportTest' --tests 'agent.ScriptedMemoryChatApiTest'
./gradlew souzGateFast
```

These focused support tests use a fake provider/HTTP boundary and need neither Hindsight nor provider keys. They verify both real graphs, tool execution, history propagation, capture identity, read-only evaluation, swallowed HTTP failures, source attribution, skipped checks and legacy manifest replay. Injected LLMs prohibit provider client construction in the test DI.
