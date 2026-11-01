# JVM Skill plugin reference evidence

The fixtures exercise the shared reference cases for issues #957/#958. They are compiled into real
JARs outside the host test runtime classpath; no plugin implementation is linked into the host.

| Case | Executable evidence |
| --- | --- |
| Java/Kotlin SDK, private dependency/resource/SPI conflicts | [PluginLoaderTest](../src/test/kotlin/ru/souz/backend/plugins/PluginLoaderTest.kt); two Kotlin versions use Commons CSV 1.10/1.9 with the same implementation class names. Java uses the same suspending SDK ABI. |
| Owner state, conditional concurrent writes, plugin/owner isolation | [PluginRepositoryTest](../src/test/kotlin/ru/souz/backend/plugins/PluginRepositoryTest.kt); real PostgreSQL records and CAS. |
| Java HTTP completion/cancellation and credential isolation | [PluginHttpTest](../src/test/kotlin/ru/souz/backend/plugins/PluginHttpTest.kt); real host client and controlled local server. |
| In-flight update, retirement, disable, rollback, capacity and failures | [PluginRuntimeTest](../src/test/kotlin/ru/souz/backend/plugins/PluginRuntimeTest.kt); bounded external gates release actual blocking work and prove JAR deletion after users exit. |
| Operator install through ordinary bootstrap/Skill requests | [BackendPluginE2eTest](../src/test/kotlin/ru/souz/backend/e2e/BackendPluginE2eTest.kt); running production services, Java HTTP, state, explicit selections and disable/re-enable. |
| Child permissions, trusted owner and pinned version | [BackendPluginSubagentE2eTest](../src/test/kotlin/ru/souz/backend/e2e/BackendPluginSubagentE2eTest.kt); update while the structured child is held. |
| File/composite coexistence and no container for a plugin-only request | [BackendPluginSandboxE2eTest](../src/test/kotlin/ru/souz/backend/e2e/BackendPluginSandboxE2eTest.kt); real Docker Python before/after updates, restricted composite rejection before side effects. |
| Independent rollout, failure, outage, fresh-process recovery | [PluginReplicaTest](../src/test/kotlin/ru/souz/backend/plugins/PluginReplicaTest.kt); two runtimes/pools share PostgreSQL, RLS/permissions inject registry faults, a separate JVM starts without old working files. Defective cleanup is confined to a disposable JVM, diagnosed, then corrected through operator selection. |

Run with Java 21 and Docker available:

```bash
./gradlew :backend:test --tests 'ru.souz.backend.plugins.*' --tests 'ru.souz.backend.e2e.BackendPlugin*'
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_TEST_DOCKER=1 ./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendPluginSandboxE2eTest'
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*' --rerun-tasks
```

The opt-in Docker tests require a Docker CLI on PATH; `SOUZ_DOCKER_CLI` overrides the image-build
task's CLI. A host without symbolic-link privileges skips the single host-link test using the
existing platform assumption, while command/mount/coexistence cases still execute.

## Small repeatable workload

[PluginRecoveryProbe](../src/test/kotlin/ru/souz/backend/plugins/PluginRecoveryProbe.kt) runs in a fresh
Java process launched by the replica test. It measures one Kotlin/Commons CSV plugin load from PostgreSQL,
one warm-up call, then 100 sequential calls parsing two CSV rows (`a,b\nc,d`). It also checks successful
shutdown and working-directory deletion. Lines beginning `PLUGIN_REFERENCE` appear in the Gradle XML
test output. The child is uninstrumented, with no forced GC or dedicated benchmark framework.

Two local observations on 2026-11-01 used Windows build 26300, Temurin 21.0.12.1, an Intel i5-10400F
(12 logical processors) and Docker Desktop/PostgreSQL 16. These ranges are illustrative, not an SLO:

| Observation | Value |
| --- | ---: |
| Load, verified bytes through catalog publication | 364–552 ms |
| 100 sequential calls | 156–257 ms |
| Mean / derived throughput for each batch | 1.56–2.57 ms / 389–642 calls per second |
| Process CPU during each batch, including concurrent JIT/GC | 391–578 ms |
| Used JVM heap before load / after calls, without forced GC | 41.4 MiB / 28.8–29.0 MiB |

Load timing excludes JVM startup, datasource/Flyway setup and the polling interval. The call loop includes
host admission, owner metadata, coroutine dispatch, TCCL switching and result validation; it excludes LLM
and public HTTP latency. CPU time includes all process threads, so it may exceed wall time. Heap samples
include host classes/caches and allocation/GC noise; a negative change is possible and is not evidence
of negative plugin cost. They are not retained heap, RSS or a leak proof. Repeat equivalent workloads
on the deployment hardware before comparing approaches; one tiny CSV workload cannot predict a
network-heavy or CPU-heavy plugin.

## Operational cost assumptions

One backend replica loads code once per active plugin version, shared by users. Ordinarily it retains
one version; draining updates may retain two. Artifact bytes live in PostgreSQL and each loaded version
has one disposable working JAR. Preparation/call admission and a shared bounded coroutine IO view replace
per-user processes; state remains owner-partitioned database data.

For a deployment with `N` otherwise idle per-user containers and `R` backend replicas, an estimate must
measure idle container cost `C` and the incremental loaded-plugin cost per replica `H` under the same
workload. The avoidable idle-memory term is approximately `N*C - R*H`; no dollar amount or measured
container saving is claimed here. Existing backend/JVM, database, actual computation, network traffic
and users still executing sandbox commands remain costs. Removing process startup does not remove
business-work CPU or provide container isolation.

The operating contract is [independent eventual rollout](jvm-skill-plugin-registry.md#rollout-and-diagnostics),
operator-restricted unsigned artifacts, author-managed interface/data compatibility and invocation-only
work. There is no activation barrier, stale-policy cutoff, aggregate state quota or forced termination
of foreign code. Compare those same assumptions with the other candidate before choosing a merge.
