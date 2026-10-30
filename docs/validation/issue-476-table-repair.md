# Desktop table repair — issue 476

Date: 2026-10-31 (Europe/Moscow). Checkout: `repair-s_4v354b/checkout`.
Source: `028c7dfa1f230dd80c0428efba4dad32ba97dccd` plus this repair.

The complete cell width, including padding, is bounded at 1024 dp. Longer cell text wraps; column alignment, shared widths and horizontal scrolling use the existing renderer and styling. User tables carry reference definitions from the complete message before the table source, while surrounding user text stays literal. Search indexes the table alone and adjusts source offsets for the hidden definitions.

`ChatMarkdownRenderingTest` exercises real Compose layout of 40,000 `W` characters for both roles at density 1 and 2. Its reference-link test renders cells with definitions before and after the table, verifies the renderer's resolved destinations, literal surrounding text and link-label highlighting. Both tests fail on the original PR source: oversized constraints (`262166` pixels) and unresolved `[ref]`/`[other]` destinations. The repaired focused suite passes.

All Gradle commands use the repository wrapper and Temurin 21.0.13+9-LTS:
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
Inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and `GRADLE_OPTS` are removed for final checks. Test commands use
`--init-script build/repair-476/isolate-tests.gradle --no-daemon --console=plain`.
The init script uses a temporary task-specific home, in-memory Java preferences and removes inherited provider credentials. Live-provider opt-ins remain disabled. It changes no global settings or repository automation. Raw logs and synthetic fixture/helper sources are under ignored `build/repair-476/`.

## Automated validation

The final commands below exited 0. Logs are under `build/repair-476/logs/`.

| Command (with the Java 21 environment and test-isolation flags above) | Result |
| --- | --- |
| `./gradlew :sharedUI:jvmTest --tests 'ru.souz.ui.common.ChatMarkdownRenderingTest'` | 2 passing regression tests |
| `npm ci --prefix quality` | Installed; 0 vulnerabilities |
| `./gradlew :sharedUI:build :sharedUI:jvmTest souzGateFast souzDuplicationCheck` | Build/tests and blocking fast checks pass; duplication baseline unchanged |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel` | Pass; XML/HTML reports; 7m 3s |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking` | Pass; six artifact tests; 5m 22s |

The coverage command also sets process-local `DOCKER_HOST=unix:///Users/dumch/.docker/run/docker.sock`; the Docker daemon reports 29.9.2. The first coverage invocation was interrupted (exit 130) to add explicit temporary application-log routing. Only the completed final invocation is counted as passing. Initial test scaffolding had compilation/assertion errors that were corrected before the passing regression, gate and coverage runs.

JUnit reports total 1,515 tests, zero failures/errors and 200 skips. Shared UI: 132 tests/39 skips; desktop: 337/150; backend: 330/0; native: 57/0. Skipped/live tests are not claimed as exercised. The fast gate retains only the existing advisory warnings in `DeferredToolModifyPermissionBroker.kt:81` and `MemoryUiState.kt:53`. Production duplicated tokens remain 252 and test duplicated tokens remain zero. Local duplication authority is `not_authoritative`; see [quality gates](../quality-gates.md).

## Packaged GUI validation

Joint validation completed on 2026-10-31 (Europe/Moscow) for this repaired candidate.
The owner restored native keyboard focus; Codex performed the application checks
through Computer Use. All required GUI scenarios passed.

| Scenario | Result |
| --- | --- |
| User and completed assistant tables | Headers, rows and left/center/right alignment rendered |
| Inline formatting | Bold, emphasis and inline code rendered in both roles |
| Surrounding content | User markup and reference definitions remain literal; assistant lists remain rendered |
| Horizontal scrolling | Both wide-table scrollbars visibly reached `END_WIDE_476` |
| Search | `Docs` and `code` showed matching highlights at 1/2 in the user table and 2/2 in the assistant table; dismissal retained rendering |
| Themes | Both roles readable in Light and Dark |
| Reference links | Assistant link and both user links, defined before and after the table, reached their distinct loopback endpoints with HTTP 200 |
| Oversized user cell | 40,000 `W` characters wrapped without a layout exception; trailing `AFTER_LONG_476` remained visible |
| Oversized assistant cell | The same 40,000-character fixture rendered after a short synthetic request; scrolling reached `AFTER_LONG_476` and UI remained responsive |
| Shutdown | Candidate exited through its native Quit menu; fixture server stopped |

The long user request rendered successfully but exceeded the ordinary context budget
when submitted for inference. That request is not claimed as a successful model turn.
The assistant rendering check used a separate clean chat and the short marker
`SOUZ_OVERSIZED_ASSISTANT_476`, for which the loopback server returns `oversized.md`.
No production context limit or model configuration was changed.

Original bundle: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
Isolated copy: `build/repair-476/Souz476-repair-s4v354b.app`.
The copy uses a bootstrap main, in-memory preferences, isolated home/log directories
and cleared inherited provider credentials. It selects the synthetic `table-fixture`
model at `http://127.0.0.1:19476/v1` with non-streaming responses. All 248 application
JARs match the original, as do the recorded hashes of both repaired production source
files. Both bundle fingerprints were unchanged after testing, and both bundles passed
`codesign --verify --deep --strict` before and after validation.

Bundle fingerprints (sorted relative path, NUL, binary file SHA-256):

- Original: `5f406cb80750d44672bbb3e3022f55a78b291aa7b89d483e3908b364ca382233`
- Isolated: `9c24612fe0dacdb40a60fd64117562e15ee9dd7071e1200b738fb9486447544a`

Synthetic fixtures and original SHA-256 values:

- `user.md`: `ac956fb35938661083305e4e4cfa7fa34f720df272bd56ac2fbd7e304d5cea7c`
- `assistant.md`: `6e46c784085a5aa44160cea7567124236d5e9156653108718d3088343b7d2a3a`
- `oversized.md`: `8900516fdb2661644e43667996bf37e710915e3af2dc969bab9125089c79675a`

The additional `manual-reference-links.md` exercises definitions on both sides of
the user table. Clicks reached `/link-user`, `/link-user-after` and `/link-assistant`.
The loopback fixture accepts both Content-Length and chunked requests; transport
probes passed before GUI work. Its manual-only response selector returns the oversized
fixture for the short marker above; production code and bundle JARs were unchanged.

The unattended attempt could not select the window (`cgWindowNotFound`). Exact-path
selection succeeded during joint validation, but keyboard input required owner focus.
A successful AppKit activation alone did not establish working input. Links were tested
after keyboard scenarios, and the app was closed through its native menu. No OS
permissions, global settings or real provider credentials were changed.

Structured scenario results, final fixture hashes and identity checks are retained in
`manual-validation-20261031.json` beside this checkout, outside source control.
The original blocked result and validation document are preserved there. The initial
Java-agent harness could not start because the minimal runtime omits `java.instrument`;
only the corrected bootstrap-main candidate was exercised.

Final document links and `git diff --check` pass. These checks validate this repair
only. The [original implementation report](issue-476-desktop-tables.md) describes its
own candidate. Publication CI, GitHub/local review and owner merge approval remain
required through the worker.
