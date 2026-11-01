# Live agent evaluation runbook

Start with [agent scenario tests](../../desktopApp/docs/agent-scenario-tests.md) for catalog,
fixtures, checks, and execution limits. Use Java 21 and the repository wrapper. Verify
`java -version` and `./gradlew --version`; on this macOS host the installed Homebrew Java
21 home is `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

## AITunnel example and availability

The names `OPENAI_*` configure Souz's custom compatible adapter, including non-OpenAI
providers. Keep the key in the environment and record only non-secret configuration:

```bash
export OPENAI_API_KEY="${AITUNNEL_KEY:?AITUNNEL_KEY must be set}"
export OPENAI_BASE_URL='https://api.aitunnel.ru/v1'
export OPENAI_MODEL='qwen3.7-flash'
export SOUZ_AGENT_INTEGRATION_TESTS_ON=true
export SOUZ_AGENT_INTEGRATION_TEST_MODEL='openai-compatible-custom'
export SOUZ_AGENT_INTEGRATION_TEST_AGENT='skills'
export SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT='high'
export SOUZ_CLASSIFIER='llm'
```

Confirm the exact model in the authenticated provider catalog before a live test. Stop if
it is absent or availability cannot be established; do not substitute another model.
This read prints no credential:

```bash
python3 - <<'PY'
import json, os, urllib.request
request = urllib.request.Request(
    os.environ['OPENAI_BASE_URL'].rstrip('/') + '/models',
    headers={'Authorization': 'Bearer ' + os.environ['OPENAI_API_KEY']},
)
with urllib.request.urlopen(request, timeout=30) as response:
    models = json.load(response)['data']
model = os.environ['OPENAI_MODEL']
if not any(entry['id'] == model for entry in models):
    raise SystemExit('Model unavailable: ' + model)
print('Model available: ' + model)
PY
```

Run the deterministic checks from the scenario guide, then the three launch cases:

```bash
./gradlew :desktopApp:test --rerun \
  --tests 'agent.GraphAgentToolScenariosIntegrationTest.scenario1_launchApplication*' \
  --no-parallel --console=plain
```

Verify the chosen agent/model, endpoint, three cases, and a completed discovery → tool →
final-answer chain. One text response does not establish tool-continuation compatibility.
Investigate configuration or HTTP 400 errors before the full run. Preserve smoke results
separately: a completed chain confirms compatibility, not provider stability.

## Preserve an independent full run

The report directory must be new. Redirect JUnit, HTML, binary output, and runtime logs
before execution so later tests cannot overwrite it and old reports cannot masquerade as
fresh data. The same isolation can be used for smoke runs by changing the filter.

```bash
run_dir="$(pwd)/build/reports/agent-scenarios-$(TZ=Europe/Moscow date +%Y%m%d-%H%M%S)"
mkdir -p "$run_dir"
git rev-parse HEAD > "$run_dir/commit.txt"
git diff > "$run_dir/source.patch"
model="$SOUZ_AGENT_INTEGRATION_TEST_MODEL"
endpoint='native'
if [ "$model" = 'openai-compatible-custom' ]; then
  model="$OPENAI_MODEL"
  endpoint="$OPENAI_BASE_URL"
fi
printf 'model=%s\nbase_url=%s\nagent=%s\nreasoning=%s\ncatalog=full+skills-2026-10-29\n' \
  "$model" "$endpoint" \
  "$SOUZ_AGENT_INTEGRATION_TEST_AGENT" "$SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT" > "$run_dir/config.txt"
cat > "$run_dir/progress.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        if (path == ':desktopApp:test') {
            def isolated = new File(System.getProperty('souz.scenario.reportDir'))
            binaryResultsDirectory.set(new File(isolated, 'binary'))
            reports.junitXml.outputLocation.set(new File(isolated, 'junit'))
            reports.html.outputLocation.set(new File(isolated, 'junit-html'))
            onOutput { descriptor, event ->
                new File(isolated, 'live-output.log').append(event.message)
            }
            afterTest { descriptor, result ->
                println('LIVE_CASE ' + result.resultType + ' ' + descriptor.displayName + ' durationMs=' + (result.endTime - result.startTime))
            }
        }
    }
}
GRADLE
if SOUZ_LOG_DIR="$run_dir/runtime-logs" ./gradlew :desktopApp:test --rerun \
  --tests agent.GraphAgentToolScenariosIntegrationTest --no-parallel --console=plain \
  -I "$run_dir/progress.init.gradle" -Dsouz.scenario.reportDir="$run_dir" \
  > "$run_dir/gradle.log" 2>&1
then run_status=0
else run_status=$?
fi
printf '%s\n' "$run_status" > "$run_dir/exit-code.txt"
printf 'Gradle exit: %s\nReports: %s\n' "$run_status" "$run_dir"
```

Never save `env`, credentials, or login caches. Diagnose this run from its isolated logs,
not a shared application log containing unrelated processes.

## Result checks and recording

- Require 94 JUnit cases, zero skipped, and 94 `Scenario metrics` entries. Keep original
  statuses and exit code. Exit code 1 can mean failed cases; absent fresh JUnit means no score.
- Record the fixture commit/catalog, provider, requested and returned model, agent,
  reasoning, streaming, context, temperature transmission, and explicit API parameters.
  The custom compatible adapter omits temperature (fixture value 0) and sends
  `max_completion_tokens`. A requested reasoning level does not establish server behavior.
- Record Gradle wall time separately from summed scenario/JUnit time, HTTP count and
  average latency, model/tool calls, and prompt/completion/total/cached tokens. Compare
  the sum of the 94 scenario usages with session `Spent`. Cached tokens are already part
  of prompt tokens; do not add them to total. This is parsed-response usage, not billing.
- Separate assertion failures, provider errors, 60-second request timeouts, and four-minute
  scenario timeouts. Internal HTTP 503 retries do not increase `modelCalls`; count them
  separately. Infrastructure failures cannot establish model quality on those cases,
  but remain in the original score. Record any manual reassessment separately.

Write results to [Тесты гигадеск](https://docs.google.com/spreadsheets/d/1Mn1bjM2pIfXvz81b1ZUsvHStuT0ueRdsfhs7cAYMDkM/edit).
Read the current headers and next empty row; preserve formatting and formulas. Put failure
categories, configuration, fixture revision, and report location in the `Подробности`
cell note. Re-read the written cells and check the calculated percentage.
