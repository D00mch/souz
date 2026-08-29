import subprocess
import tempfile
import unittest
from pathlib import Path


CREATE_COMMIT = Path(__file__).with_name("create_repowise_pr_commit.sh")


def git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


class RepoWisePrCommitTest(unittest.TestCase):
    def test_stale_pr_becomes_one_commit_on_the_current_base(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            git(repository, "init")
            git(repository, "config", "user.name", "Test Author")
            git(repository, "config", "user.email", "author@example.com")

            (repository / "base.txt").write_text("original base\n")
            (repository / "pr.txt").write_text("original PR\n")
            git(repository, "add", ".")
            git(repository, "commit", "-m", "Initial")
            common_ancestor = git(repository, "rev-parse", "HEAD")

            git(repository, "checkout", "-b", "pr")
            (repository / "pr.txt").write_text("first PR version\n")
            git(repository, "commit", "-am", "First PR commit")
            (repository / "pr.txt").write_text("final PR version\n")
            git(repository, "commit", "-am", "Second PR commit")
            pr_head = git(repository, "rev-parse", "HEAD")

            git(repository, "checkout", "-b", "base", common_ancestor)
            (repository / "base.txt").write_text("current base\n")
            git(repository, "commit", "-am", "Advance base")
            base = git(repository, "rev-parse", "HEAD")

            self.assertEqual(
                common_ancestor,
                git(repository, "merge-base", base, pr_head),
            )
            self.assertNotEqual(base, common_ancestor)

            result = subprocess.run(
                [
                    CREATE_COMMIT,
                    base,
                    pr_head,
                    "Synthetic PR",
                    "PR Author",
                    "pr-author@example.com",
                    "2026-01-02T03:04:05Z",
                ],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            )
            synthetic = result.stdout.strip()

            self.assertEqual(
                base,
                git(repository, "show", "-s", "--format=%P", synthetic),
            )
            self.assertEqual(
                "1",
                git(repository, "rev-list", "--count", f"{base}..{synthetic}"),
            )
            self.assertEqual(
                "Synthetic PR",
                git(repository, "log", "--format=%s", f"{base}..{synthetic}"),
            )
            self.assertEqual(
                "current base",
                git(repository, "show", f"{synthetic}:base.txt"),
            )
            self.assertEqual(
                "final PR version",
                git(repository, "show", f"{synthetic}:pr.txt"),
            )
            self.assertEqual(
                "pr.txt",
                git(repository, "diff", "--name-only", base, synthetic),
            )
            self.assertNotEqual(
                git(repository, "show", "-s", "--format=%T", pr_head),
                git(repository, "show", "-s", "--format=%T", synthetic),
            )
            self.assertEqual("", git(repository, "replace", "--list"))


if __name__ == "__main__":
    unittest.main()
