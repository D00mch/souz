"""Exercise SDK release guards with synthetic Git, API and distribution fixtures."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (REPO_ROOT / ".github/workflows/publish-plugin-sdk.yml").read_text()
VERIFICATION_METADATA = REPO_ROOT / "gradle/verification-metadata.xml"
VERIFICATION_NS = {"v": "https://schema.gradle.org/dependency-verification"}


def workflow_script(step_name):
    step = WORKFLOW.split(f"      - name: {step_name}\n", 1)[1]
    body = step.split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines))


class PluginSdkReleaseTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="souz-sdk-release-test-")
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                        GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                        GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid",
                        GITHUB_REF="refs/tags/plugin-api-v2.0.0", GITHUB_REPOSITORY="fixture/souz")
        self.git("init", "-q", "-b", "fixture")
        tree = self.git("mktree", input="")
        self.approved = self.git("commit-tree", tree, input="Approved release\n")
        self.tip = self.git("commit-tree", tree, "-p", self.approved, input="Later approved commit\n")
        self.unmerged = self.git("commit-tree", tree, "-p", self.tip, input="Unmerged change\n")
        self.git("update-ref", "refs/heads/fixture", self.approved)
        self.git("update-ref", "refs/remotes/origin/kmp", self.tip)
        self.git("tag", "-a", "plugin-api-v2.0.0", self.approved, "-m", "Synthetic release")
        self.env["GITHUB_SHA"] = self.approved

    def git(self, *args, input=None):
        return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=self.root,
                              env=self.env, input=input, text=True, capture_output=True,
                              check=True).stdout.strip()

    def run_guard(self, step_name):
        return subprocess.run(["bash", "-euo", "pipefail", "-c", workflow_script(step_name)],
                              cwd=self.root, env=self.env, text=True, capture_output=True)

    def assert_provenance(self, accepted):
        result = self.run_guard("Verify release provenance")
        self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)

    def test_annotated_tag_at_approved_ancestor(self):
        self.assert_provenance(True)

    def test_lightweight_tag_at_approved_tip(self):
        self.git("tag", "-f", "plugin-api-v2.0.0", self.tip)
        self.git("update-ref", "refs/heads/fixture", self.tip)
        self.env["GITHUB_SHA"] = self.tip
        self.assert_provenance(True)

    def test_unmerged_descendant_is_rejected(self):
        self.git("tag", "-f", "plugin-api-v2.0.0", self.unmerged)
        self.git("update-ref", "refs/heads/fixture", self.unmerged)
        self.env["GITHUB_SHA"] = self.unmerged
        self.assert_provenance(False)

    def test_unrelated_history_is_rejected(self):
        unrelated = self.git("commit-tree", self.git("mktree", input=""), input="Unrelated\n")
        self.git("update-ref", "refs/remotes/origin/kmp", unrelated)
        self.assert_provenance(False)

    def test_moved_tag_is_rejected(self):
        self.git("tag", "-f", "plugin-api-v2.0.0", self.tip)
        self.assert_provenance(False)

    def test_wrong_checkout_is_rejected(self):
        self.git("update-ref", "refs/heads/fixture", self.tip)
        self.assert_provenance(False)

    def test_missing_branch_is_rejected(self):
        self.git("update-ref", "-d", "refs/remotes/origin/kmp")
        self.assert_provenance(False)

    def test_missing_tag_is_rejected(self):
        self.git("tag", "-d", "plugin-api-v2.0.0")
        self.assert_provenance(False)

    def test_non_release_refs_are_rejected(self):
        for ref in ["refs/heads/kmp", "refs/tags/plugin-api-v2.0.0-rc1", "refs/tags/other"]:
            with self.subTest(ref=ref):
                self.env["GITHUB_REF"] = ref
                self.assert_provenance(False)

    def test_environment_requires_independent_reviewer(self):
        # Use the real jq predicate passed to gh, without contacting GitHub or using credentials.
        mock_bin = self.root / "bin"
        mock_bin.mkdir()
        mock_gh = mock_bin / "gh"
        mock_gh.write_text('#!/bin/sh\n'
                           '[ "$1" = api ] && '
                           '[ "$2" = repos/fixture/souz/environments/plugin-sdk-release ] && '
                           '[ "$3" = --jq ] || exit 2\n'
                           'if [ "$MOCK_API_FAILURE" = 1 ]; then echo true; exit 1; fi\n'
                           'jq -r "$4" "$MOCK_ENVIRONMENT"\n')
        mock_gh.chmod(0o700)
        response = self.root / "environment.json"
        self.env.update(PATH=str(mock_bin) + os.pathsep + self.env["PATH"],
                        MOCK_ENVIRONMENT=str(response), MOCK_API_FAILURE="0", GH_TOKEN="synthetic")
        reviewer = {"type": "required_reviewers", "prevent_self_review": True,
                    "reviewers": [{"type": "User", "reviewer": {"login": "release-reviewer"}}]}
        cases = [
            ("protected", {"protection_rules": [reviewer]}, True),
            ("unprotected", {"protection_rules": []}, False),
            ("missing rules", {}, False),
            ("timer only", {"protection_rules": [{"type": "wait_timer"}]}, False),
            ("no reviewers", {"protection_rules": [dict(reviewer, reviewers=[])]}, False),
            ("self approval", {"protection_rules": [dict(reviewer, prevent_self_review=False)]}, False),
        ]
        for name, environment, accepted in cases:
            with self.subTest(environment=name):
                response.write_text(json.dumps(environment))
                result = self.run_guard("Require release approval protection")
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
        self.env["MOCK_API_FAILURE"] = "1"
        result = self.run_guard("Require release approval protection")
        self.assertNotEqual(result.returncode, 0, "API errors must fail closed even with stdout")

    def test_secret_job_requires_preflight_and_environment(self):
        preflight, publish = WORKFLOW.split("\n  publish:\n", 1)
        self.assertNotIn("${{ secrets.", preflight)
        self.assertRegex(publish, r"(?m)^    needs: verify-source$")
        self.assertRegex(publish, r"(?m)^    environment: plugin-sdk-release$")
        self.assertEqual(WORKFLOW.count("ref: ${{ github.sha }}"), 2)
        self.assertEqual(WORKFLOW.count("persist-credentials: false"), 2)
        self.assertIn("fetch-depth: 0", preflight)
        self.assertIn("cache-disabled: true", publish)

    def test_all_release_actions_use_full_commit_pins(self):
        actions = re.findall(r"(?m)^\s+uses: (\S+)", WORKFLOW)
        self.assertTrue(actions)
        for action in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[\w./-]+@[0-9a-f]{40}$")

    def test_release_jdk_is_authenticated_before_gradle_and_secrets(self):
        publish = WORKFLOW.split("\n  publish:\n", 1)[1]
        before_jdk, after_jdk = publish.split("      - name: Set up JDK\n", 1)
        jdk, after_jdk = after_jdk.split("      - name:", 1)
        # v6.3.0 enforces explicit signature verification with the bundled Adoptium key.
        # Force a download so an unverified runner tool-cache entry cannot bypass it.
        self.assertIn("uses: actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880", jdk)
        for setting in ["distribution: temurin", "java-version: 21", "force-download: true",
                        "verify-signature: true"]:
            self.assertRegex(jdk, rf"(?m)^          {re.escape(setting)}$")
        self.assertNotIn("jdk-file:", jdk)
        self.assertNotIn("verify-signature-public-key:", jdk)
        self.assertNotIn("./gradlew", before_jdk)
        self.assertNotIn("${{ secrets.", before_jdk)
        self.assertIn("./gradlew", after_jdk)
        self.assertIn("${{ secrets.MAVEN_SIGNING_KEY }}", after_jdk)

    def test_release_dependencies_require_reviewed_hashes(self):
        commands = [line.strip() for line in WORKFLOW.splitlines() if "./gradlew " in line]
        self.assertEqual(len(commands), 2)
        for command in commands:
            self.assertIn("--dependency-verification=strict", command)
            self.assertNotIn("--write-verification-metadata", command)
        metadata = ET.parse(VERIFICATION_METADATA)
        self.assertEqual(metadata.findtext("v:configuration/v:verify-metadata",
                                          namespaces=VERIFICATION_NS), "true")
        self.assertIsNone(metadata.find("v:configuration/v:trusted-artifacts", VERIFICATION_NS))
        # Authenticate both the plugin marker and executable implementation, including metadata.
        for group, name, artifacts in [
            ("com.vanniktech.maven.publish.base", "com.vanniktech.maven.publish.base.gradle.plugin",
             ["com.vanniktech.maven.publish.base.gradle.plugin-0.37.0.pom"]),
            ("com.vanniktech", "gradle-maven-publish-plugin",
             ["gradle-maven-publish-plugin-0.37.0.jar", "gradle-maven-publish-plugin-0.37.0.module"]),
        ]:
            component = metadata.find(f"v:components/v:component[@group='{group}']"
                                      f"[@name='{name}'][@version='0.37.0']", VERIFICATION_NS)
            self.assertIsNotNone(component)
            for artifact in artifacts:
                checksum = component.find(f"v:artifact[@name='{artifact}']/v:sha256", VERIFICATION_NS)
                self.assertIsNotNone(checksum, artifact)
                self.assertRegex(checksum.get("value"), r"^[0-9a-f]{64}$")


class GradleDependencyVerificationTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="souz-sdk-dependency-test-")
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        (self.root / "settings.gradle").write_text("rootProject.name = 'verification-fixture'\n")
        (self.root / "gradle").mkdir()
        self.metadata = self.root / "gradle/verification-metadata.xml"
        shutil.copy2(VERIFICATION_METADATA, self.metadata)
        self.marker = self.root / "build-configured"

    def test_standalone_exempts_only_the_compile_only_sdk_under_test(self):
        verifier = (REPO_ROOT / "scripts/verify-plugin-sdk.sh").read_text()
        self.assertIn("--dependency-verification=strict", verifier)
        script = verifier.split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        shutil.copy2(REPO_ROOT / "plugin-api/examples/hello/build.gradle.kts", self.root)
        subprocess.run([sys.executable, "-c", script, str(VERIFICATION_METADATA), str(self.root)],
                       check=True, capture_output=True, text=True)
        metadata = ET.parse(self.metadata)
        configuration = metadata.find("v:configuration", VERIFICATION_NS)
        self.assertEqual(configuration.findtext("v:verify-metadata", namespaces=VERIFICATION_NS), "true")
        trusted = configuration.findall("v:trusted-artifacts/v:trust", VERIFICATION_NS)
        self.assertEqual(len(trusted), 1)
        self.assertEqual({key: value for key, value in trusted[0].attrib.items() if key != "reason"},
                         {"group": "io.github.d00mch", "name": "plugin-api", "version": "2.0.0"})
        self.assertEqual(ET.canonicalize(ET.tostring(metadata.find("v:components", VERIFICATION_NS))),
                         ET.canonicalize(ET.tostring(ET.parse(VERIFICATION_METADATA).find(
                             "v:components", VERIFICATION_NS))))

    def test_supported_desktop_runtime_artifacts_are_pinned(self):
        metadata = ET.parse(VERIFICATION_METADATA)
        for platform in ["linux-x64", "linux-arm64", "macos-x64", "macos-arm64",
                         "windows-x64", "windows-arm64"]:
            desktop = f"desktop-jvm-{platform}"
            component = metadata.find("v:components/v:component[@group='org.jetbrains.compose.desktop']"
                                      f"[@name='{desktop}'][@version='1.9.3']", VERIFICATION_NS)
            self.assertIsNotNone(component, platform)
            self.assertIsNotNone(component.find(f"v:artifact[@name='{desktop}-1.9.3.pom']/v:sha256",
                                                VERIFICATION_NS))
            name = f"skiko-awt-runtime-{platform}"
            component = metadata.find(f"v:components/v:component[@group='org.jetbrains.skiko']"
                                      f"[@name='{name}'][@version='0.9.22.2']", VERIFICATION_NS)
            self.assertIsNotNone(component, platform)
            for extension in ["pom", "jar"]:
                self.assertIsNotNone(component.find(f"v:artifact[@name='{name}-0.9.22.2.{extension}']"
                                                    "/v:sha256", VERIFICATION_NS))
        component = metadata.find("v:components/v:component[@group='it.tdlight']"
                                  "[@name='tdlight-natives'][@version='4.0.558']", VERIFICATION_NS)
        for classifier in ["macos_arm64", "macos_amd64", "windows_amd64",
                           "linux_arm64_gnu_ssl3", "linux_amd64_gnu_ssl3"]:
            self.assertIsNotNone(component.find(
                f"v:artifact[@name='tdlight-natives-4.0.558-{classifier}.jar']/v:sha256", VERIFICATION_NS))

    def test_ci_build_metadata_is_pinned(self):
        metadata = ET.parse(VERIFICATION_METADATA)
        # Parent/BOM metadata is resolved even when its dependencies lose version selection.
        for group, name, version, extensions in [
            ("com.fasterxml", "oss-parent", "68", ["pom"]),
            ("com.fasterxml.jackson", "jackson-base", "2.15.2", ["pom"]),
            ("com.fasterxml.jackson", "jackson-base", "2.21.3", ["pom"]),
            ("com.fasterxml.jackson", "jackson-base", "2.22.1", ["pom"]),
            ("com.fasterxml.jackson", "jackson-bom", "2.19.1", ["pom"]),
            ("com.fasterxml.jackson", "jackson-parent", "2.19.2", ["pom"]),
            ("com.fasterxml.jackson.dataformat", "jackson-dataformats-text", "2.15.2", ["pom"]),
            ("com.fasterxml.jackson.dataformat", "jackson-dataformats-text", "2.21.3", ["pom"]),
            ("com.fasterxml.jackson.dataformat", "jackson-dataformats-text", "2.22.1", ["pom"]),
            ("com.fasterxml.jackson.module", "jackson-modules-java8", "2.15.2", ["pom"]),
            ("com.fasterxml.jackson.module", "jackson-modules-java8", "2.21.3", ["pom"]),
            ("com.fasterxml.jackson.module", "jackson-modules-java8", "2.22.1", ["pom"]),
            ("io.opentelemetry", "opentelemetry-bom", "1.50.0", ["module"]),
            ("io.opentelemetry", "opentelemetry-bom-alpha", "1.50.0-alpha", ["module"]),
            ("io.opentelemetry.instrumentation", "opentelemetry-instrumentation-bom", "2.16.0", ["module"]),
            ("io.opentelemetry.instrumentation", "opentelemetry-instrumentation-bom-alpha", "2.16.0-alpha", ["module"]),
            ("org.apache.groovy", "groovy-bom", "4.0.22", ["module"]),
            ("org.apache.groovy", "groovy-bom", "4.0.27", ["pom", "module"]),
            ("org.apache.logging.log4j", "log4j", "2.25.1", ["pom"]),
            ("org.bouncycastle", "bc-jdk18on-bom", "1.84", ["module"]),
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.6.4", ["pom"]),
            ("org.jetbrains.kotlinx", "kotlinx-serialization-bom", "1.6.2", ["pom"]),
            ("org.junit", "junit-bom", "5.7.0", ["module"]),
            ("org.junit", "junit-bom", "5.8.2", ["pom", "module"]),
            ("org.junit", "junit-bom", "5.9.0", ["module"]),
            ("org.junit", "junit-bom", "5.9.1", ["pom", "module"]),
            ("org.junit", "junit-bom", "5.9.3", ["module"]),
            ("org.junit", "junit-bom", "5.10.0", ["module"]),
            ("org.junit", "junit-bom", "5.10.3", ["module"]),
            ("org.junit", "junit-bom", "5.11.0-M2", ["module"]),
            ("org.junit", "junit-bom", "5.11.4", ["module"]),
            ("org.junit", "junit-bom", "5.12.2", ["module"]),
            ("org.junit", "junit-bom", "5.13.0", ["module"]),
            ("org.junit", "junit-bom", "5.13.1", ["module"]),
            ("org.junit", "junit-bom", "5.13.2", ["pom", "module"]),
            ("org.junit", "junit-bom", "5.13.4", ["module"]),
            ("org.junit", "junit-bom", "5.14.1", ["pom", "module"]),
            ("org.junit", "junit-bom", "5.14.3", ["pom", "module"]),
            ("org.junit", "junit-bom", "5.14.4", ["module"]),
            ("org.junit", "junit-bom", "6.0.1", ["module"]),
            ("org.junit", "junit-bom", "6.0.2", ["pom"]),
            ("org.springframework", "spring-framework-bom", "5.3.29", ["module"]),
            ("org.springframework", "spring-framework-bom", "5.3.39", ["module"]),
        ]:
            component = metadata.find(f"v:components/v:component[@group='{group}']"
                                      f"[@name='{name}'][@version='{version}']", VERIFICATION_NS)
            self.assertIsNotNone(component, (group, name, version))
            for extension in extensions:
                checksum = component.find(f"v:artifact[@name='{name}-{version}.{extension}']/v:sha256",
                                          VERIFICATION_NS)
                self.assertIsNotNone(checksum)
                self.assertRegex(checksum.get("value"), r"^[0-9a-f]{64}$")

    def test_settings_plugin_sources_are_pinned_for_ide_import(self):
        metadata = ET.parse(VERIFICATION_METADATA)
        checksum = metadata.find("v:components/v:component[@group='org.gradle.toolchains']"
                                 "[@name='foojay-resolver'][@version='1.0.0']/v:artifact"
                                 "[@name='foojay-resolver-1.0.0-sources.jar']/v:sha256", VERIFICATION_NS)
        self.assertIsNotNone(checksum)
        self.assertRegex(checksum.get("value"), r"^[0-9a-f]{64}$")

    def resolve_plugin(self, version="0.37.0"):
        repository = self.root / "repository/com/vanniktech/gradle-maven-publish-plugin" / version
        repository.mkdir(parents=True)
        artifact = repository / f"gradle-maven-publish-plugin-{version}.jar"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr("fixture.txt", "Synthetic substituted plugin")
        (self.root / "build.gradle").write_text(textwrap.dedent(f"""\
            buildscript {{
                repositories {{
                    maven {{ url = uri('repository'); metadataSources {{ artifact() }} }}
                }}
                dependencies {{ classpath 'com.vanniktech:gradle-maven-publish-plugin:{version}' }}
            }}
            file('build-configured').text = 'Build script evaluated'
            tasks.register('verifyFixture')
            """))
        return artifact

    def run_gradle(self):
        env = dict(os.environ)
        for option in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                       "_JAVA_OPTIONS"):
            env.pop(option, None)
        # Use the installed wrapper with offline, fixture-only dependency resolution.
        gradle_home = env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
        return subprocess.run(["bash", str(REPO_ROOT / "gradlew"), "-g", gradle_home,
                               f"-Duser.home={self.root}", "--no-daemon", "--offline",
                               "--no-configuration-cache", "--no-build-cache",
                               "--dependency-verification=strict", "--console=plain", "verifyFixture"],
                              cwd=self.root, env=env, text=True, capture_output=True, timeout=90)

    def assert_rejected(self):
        result = self.run_gradle()
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn("Dependency verification failed", output)
        self.assertFalse(self.marker.exists(), "Unverified buildscript dependency must not be loaded")

    def test_substituted_publishing_plugin_is_rejected(self):
        self.resolve_plugin()
        self.assert_rejected()

    def test_unlisted_publishing_plugin_is_rejected(self):
        self.resolve_plugin("0.0.0-fixture")
        self.assert_rejected()

    def test_reviewed_fixture_hash_allows_configuration(self):
        artifact = self.resolve_plugin()
        metadata = ET.parse(self.metadata)
        component = metadata.find("v:components/v:component[@group='com.vanniktech']"
                                  "[@name='gradle-maven-publish-plugin'][@version='0.37.0']",
                                  VERIFICATION_NS)
        checksum = component.find("v:artifact[@name='gradle-maven-publish-plugin-0.37.0.jar']/v:sha256",
                                  VERIFICATION_NS)
        checksum.set("value", hashlib.sha256(artifact.read_bytes()).hexdigest())
        ET.register_namespace("", VERIFICATION_NS["v"])
        metadata.write(self.metadata, encoding="UTF-8", xml_declaration=True)
        result = self.run_gradle()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.marker.exists())


class GradleDistributionTest(unittest.TestCase):
    # https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256
    CHECKSUM = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"

    def test_release_distribution_has_published_checksum(self):
        properties = (REPO_ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip\n",
                      properties)
        self.assertRegex(properties, rf"(?m)^distributionSha256Sum={self.CHECKSUM}$")

    def test_wrapper_rejects_substituted_distribution_before_extraction(self):
        with tempfile.TemporaryDirectory(prefix="souz-sdk-wrapper-test-") as scratch:
            root = Path(scratch)
            wrapper = root / "gradle/wrapper"
            wrapper.mkdir(parents=True)
            shutil.copy2(REPO_ROOT / "gradlew", root / "gradlew")
            shutil.copy2(REPO_ROOT / "gradle/wrapper/gradle-wrapper.jar", wrapper)
            distribution = root / "gradle-substituted.zip"
            with zipfile.ZipFile(distribution, "w") as archive:
                archive.writestr("gradle-substituted/README", "Synthetic untrusted distribution")
            properties = (REPO_ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
            properties = re.sub(r"(?m)^distributionUrl=.*$",
                                "distributionUrl=" + distribution.as_uri(), properties)
            (wrapper / "gradle-wrapper.properties").write_text(properties)
            env = dict(os.environ)
            for option in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                           "_JAVA_OPTIONS"):
                env.pop(option, None)
            gradle_home = root / "gradle-home"
            result = subprocess.run(["bash", str(root / "gradlew"), "-g", str(gradle_home),
                                     f"-Duser.home={root}", "--version"], cwd=root, env=env,
                                    text=True, capture_output=True, timeout=30)
            output = result.stdout + result.stderr
            self.assertNotEqual(result.returncode, 0, output)
            self.assertIn("Verification of Gradle distribution failed!", output)
            self.assertIn(self.CHECKSUM, output)
            self.assertFalse(list(gradle_home.rglob("README")), "Untrusted ZIP must not be extracted")


if __name__ == "__main__":
    unittest.main()
