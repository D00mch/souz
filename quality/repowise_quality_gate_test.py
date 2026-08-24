import unittest

from quality.repowise_quality_gate import (
    QUALITY_KPIS,
    compare_health,
    render_markdown,
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


if __name__ == "__main__":
    unittest.main()
