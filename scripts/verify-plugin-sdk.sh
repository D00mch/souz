#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
temp_root="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
scratch="$(mktemp -d "$temp_root/souz-plugin-sdk.XXXXXX")"
scratch="$(cd "$scratch" && pwd -P)"
[[ "$scratch" == "$temp_root"/souz-plugin-sdk.* ]] || exit 1
trap 'rm -rf -- "$scratch"' EXIT

# Copy only the standalone project and Gradle bootstrap, without repository build logic.
cp "$repo_root/plugin-api/examples/hello/build.gradle.kts" "$scratch/"
cp "$repo_root/plugin-api/examples/hello/settings.gradle.kts" "$scratch/"
cp -R "$repo_root/plugin-api/examples/hello/src" "$scratch/src"
cp "$repo_root/gradlew" "$scratch/"
mkdir -p "$scratch/gradle/wrapper" "$scratch/maven-local"
cp "$repo_root/gradle/wrapper/gradle-wrapper.jar" "$scratch/gradle/wrapper/"
cp "$repo_root/gradle/wrapper/gradle-wrapper.properties" "$scratch/gradle/wrapper/"

cd "$scratch"
# A fresh Gradle home also prevents a previously cached SDK from hiding a missing publication.
bash ./gradlew --gradle-user-home "$scratch/gradle-home" \
    -Dmaven.repo.local="$scratch/maven-local" -Pkotlin.compiler.execution.strategy=in-process \
    --no-daemon --console=plain jar

plugin_jar=build/libs/souz-plugin-hello.jar
jar tf "$plugin_jar" > jar-entries.txt
grep -qx 'example/HelloPlugin.class' jar-entries.txt
grep -qx 'META-INF/services/ru.souz.plugins.api.SouzPlugin' jar-entries.txt
if grep -Eq '^(ru/souz/plugins/api/|kotlin/|kotlinx/coroutines/)' jar-entries.txt; then
    echo 'The plugin JAR must not bundle host-provided SDK, Kotlin or coroutine classes.' >&2
    exit 1
fi
if [[ -n "$(ls -A maven-local)" ]]; then
    echo 'The standalone build must leave Maven local empty.' >&2
    exit 1
fi
echo 'Standalone Hello plugin built with an empty Maven local and no Souz checkout.'
