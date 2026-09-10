# Container builds

Kaniko must snapshot a stable filesystem after the Gradle build. JVM shutdown can delete `/tmp/hsperfdata_<user>/<pid>` during that scan, failing image creation even when `:backend:installDist` succeeds. Gradle can launch a single-use daemon with `--no-daemon`.

The Gradle invocation in [backend.Dockerfile](../../../backend.Dockerfile) sets `-XX:-UsePerfData` through `JAVA_TOOL_OPTIONS`, preserving existing options and propagating the flag to child JVMs. Keep this setting scoped to the image build; Helm environment values only affect deployed containers and cannot fix image creation. Company-specific Dockerfiles must apply the flag to their build JVMs too.

Verify by rerunning the Kaniko image-build job and checking that its post-Gradle snapshot and image push succeed. A successful Gradle or Docker BuildKit build alone does not exercise this race.
