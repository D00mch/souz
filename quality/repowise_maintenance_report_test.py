import unittest

from quality.repowise_maintenance_report import (
    performance_payload,
    render_report,
    render_summary,
)


class RepoWiseMaintenanceReportTest(unittest.TestCase):
    def test_report_includes_detailed_plans_and_performance_fixes(self) -> None:
        health = {
            "kpis": {
                "average_health": 8.0,
                "hotspot_health": 7.0,
                "maintainability_average": 8.5,
                "maintainability_hotspot": 7.5,
                "performance_average": 9.5,
                "performance_hotspot": 9.0,
            },
            "findings": [
                {
                    "biomarker_type": "io_in_loop",
                    "file_path": "src/Store.kt",
                    "function_name": "loadAll",
                    "reason": "a database call runs once per loop iteration",
                    "details": {"boundary_kind": "db", "resolution_basis": "direct"},
                }
            ],
        }
        refactoring = {
            "targets": [
                {
                    "file_path": "src/Store.kt",
                    "score": 6.0,
                    "total_impact": 1.0,
                    "effort_bucket": "S",
                    "primary_reason": "large method",
                }
            ],
            "refactoring_plans": [
                {
                    "refactoring_type": "extract_method",
                    "file_path": "src/Store.kt",
                    "target_symbol": "loadAll",
                    "line_start": 10,
                    "confidence": "high",
                    "effort_bucket": "S",
                    "source_biomarker": "large_method",
                    "rank_score": 4.0,
                    "plan": {"suggested_name": "loadBatch", "snippet": "do not publish"},
                    "evidence": {"nloc": 80},
                    "blast_radius": {"file_count": 1},
                    "validation": {"commands": ["./gradlew test"], "tests": ["StoreTest.kt"]},
                }
            ],
        }

        performance = performance_payload(health)
        report = render_report(health, refactoring, performance, "abc123")
        summary = render_summary(refactoring, performance, "abc123")

        self.assertEqual(1, performance["summary"]["with_fix"])
        self.assertIn("## Detailed refactoring plans", report)
        self.assertIn("`./gradlew test`", report)
        self.assertNotIn("do not publish", report)
        self.assertIn("## Performance optimization", report)
        self.assertIn("Batch or prefetch I/O", report)
        self.assertIn("Top performance opportunities", summary)


if __name__ == "__main__":
    unittest.main()
