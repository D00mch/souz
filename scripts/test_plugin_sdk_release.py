"""Exercise SDK release guards with synthetic Git, API and distribution fixtures."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (REPO_ROOT / ".github/workflows/publish-plugin-sdk.yml").read_text()


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
