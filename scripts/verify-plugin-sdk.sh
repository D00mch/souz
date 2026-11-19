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
# Keep third-party build code verified in the standalone project as well. The compile-only
# SDK is the publication under test, so exempt only its exact coordinates in this temporary copy.
python3 - "$repo_root/gradle/verification-metadata.xml" "$scratch" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as E

source, scratch = Path(sys.argv[1]), Path(sys.argv[2])
namespace = "https://schema.gradle.org/dependency-verification"
E.register_namespace("", namespace)
metadata = E.parse(source)
sdk = re.search(r'compileOnly\("([^":]+):([^":]+):([^":]+)"\)',
                (scratch / "build.gradle.kts").read_text())
if sdk is None:
    raise SystemExit("Expected an exact compile-only SDK coordinate")
trusted = E.SubElement(metadata.find(f"{{{namespace}}}configuration"), f"{{{namespace}}}trusted-artifacts")
E.SubElement(trusted, f"{{{namespace}}}trust", group=sdk[1], name=sdk[2], version=sdk[3],
             reason="Compile-only SDK publication under test; third-party build code remains verified")
metadata.write(scratch / "gradle/verification-metadata.xml", encoding="UTF-8", xml_declaration=True)
PY

cd "$scratch"
# A fresh Gradle home also prevents a previously cached SDK from hiding a missing publication.
bash ./gradlew --gradle-user-home "$scratch/gradle-home" \
    -Dmaven.repo.local="$scratch/maven-local" -Pkotlin.compiler.execution.strategy=in-process \
    --dependency-verification=strict --no-daemon --console=plain jar

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
