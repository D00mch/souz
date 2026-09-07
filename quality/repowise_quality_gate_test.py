import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from quality.repowise_quality_gate import QUALITY_KPIS, main


class RepoWiseQualityGateTest(unittest.TestCase):
    def test_main_exit_status(self) -> None:
        for score, expected_exit, result in [
            (7.5, 0, "REGRESSION"),
            ("bad", 2, "ERROR"),
        ]:
            with self.subTest(result=result), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reports = {
                    "base": {"kpis": {key: 8.0 for key, _ in QUALITY_KPIS}},
                    "head": {"kpis": {key: score for key, _ in QUALITY_KPIS}},
                    "risk": {
                        "classification": "Typical",
                        "review_priority": "moderate",
                        "score": 7.2,
                        "risk_percentile": 61.5,
                        "baseline_sample_size": 200,
                        "features": {"la": 30, "ld": 12, "nf": 4, "nd": 2, "ns": 1, "entropy": 1.25},
                    },
                }
                for name, data in reports.items():
                    (root / name).write_text(json.dumps(data), encoding="utf-8")
                markdown = root / "quality-ratchet.md"
                with contextlib.redirect_stderr(io.StringIO()):
                    exit_code = main([
                        "--base", str(root / "base"),
                        "--head", str(root / "head"),
                        "--risk", str(root / "risk"),
                        "--markdown", str(markdown),
                        "--base-sha", "base", "--head-sha", "head",
                    ])
                self.assertEqual(expected_exit, exit_code)
                summary = markdown.read_text(encoding="utf-8")
                self.assertIn(f"advisory: {result}", summary)
                if expected_exit == 0:
                    self.assertIn("PR change risk", summary)


if __name__ == "__main__":
    unittest.main()
