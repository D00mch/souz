import unittest

from quality.repowise_quality_gate import (
    QUALITY_KPIS,
    compare_health,
    render_markdown,
    render_risk_markdown,
)


def report(score: float, **overrides: float) -> dict:
    kpis = {key: score for key, _ in QUALITY_KPIS}
    kpis.update(overrides)
    return {"kpis": kpis}


class RepoWiseQualityGateTest(unittest.TestCase):
    def test_equal_and_improved_scores_pass(self) -> None:
        comparisons = compare_health(
            report(8.0), report(8.0, average_health=8.1)
        )

        self.assertFalse(any(comparison.regressed for comparison in comparisons))
        self.assertIn("ratchet: PASS", render_markdown(comparisons, "base", "head"))

    def test_any_decrease_fails(self) -> None:
        comparisons = compare_health(
            report(8.0), report(8.0, maintainability_hotspot=7.99)
        )

        self.assertTrue(any(comparison.regressed for comparison in comparisons))
        markdown = render_markdown(comparisons, "base", "head")
        self.assertIn("ratchet: FAIL", markdown)
        self.assertIn("| Maintainability · hotspots | 8.00 | 7.99 | -0.01 | regressed |", markdown)

    def test_missing_kpi_is_rejected(self) -> None:
        head = report(8.0)
        del head["kpis"]["performance_average"]

        with self.assertRaisesRegex(ValueError, "performance_average"):
            compare_health(report(8.0), head)

    def test_pr_risk_is_rendered_from_the_diff_metrics(self) -> None:
        risk = {
            "classification": "Typical",
            "review_priority": "moderate",
            "score": 7.2,
            "risk_percentile": 61.5,
            "baseline_sample_size": 200,
            "features": {"la": 30, "ld": 12, "nf": 4, "nd": 2, "ns": 1, "entropy": 1.25},
            "drivers": [
                {"label": "more lines added", "value": 30, "contribution": 0.5},
                {"label": "experienced author", "value": 10, "contribution": -0.1},
            ],
        }

        markdown = render_risk_markdown(risk)

        self.assertIn("## PR change risk", markdown)
        self.assertIn("| 30 | 12 | 4 | 2 | 1 | 1.25 |", markdown)
        self.assertIn("more lines added", markdown)
        self.assertIn("-0.100", markdown)


if __name__ == "__main__":
    unittest.main()
