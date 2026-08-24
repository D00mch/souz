#!/usr/bin/env python3
"""Fail when RepoWise repository health decreases from a PR base."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


QUALITY_KPIS = (
    ("average_health", "Defect health · average"),
    ("hotspot_health", "Defect health · hotspots"),
    ("worst_performer_score", "Defect health · worst file"),
    ("maintainability_average", "Maintainability · average"),
    ("maintainability_hotspot", "Maintainability · hotspots"),
    ("performance_average", "Performance · average"),
    ("performance_hotspot", "Performance · hotspots"),
)


@dataclass(frozen=True)
class Comparison:
    key: str
    label: str
    base: float
    head: float

    @property
    def delta(self) -> float:
        return self.head - self.base

    @property
    def regressed(self) -> bool:
        return self.delta < 0


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read RepoWise report {path}: {error}") from error
    if not isinstance(report, dict):
        raise ValueError(f"RepoWise report {path} is not a JSON object")
    return report


def load_health_report(path: Path) -> dict[str, Any]:
    report = load_json_object(path)
    if not isinstance(report.get("kpis"), dict):
        raise ValueError(f"RepoWise report {path} has no KPI object")
    return report


def compare_health(
    base_report: dict[str, Any], head_report: dict[str, Any]
) -> list[Comparison]:
    base_kpis = base_report["kpis"]
    head_kpis = head_report["kpis"]
    return [
        Comparison(
            key=key,
            label=label,
            base=_score(base_kpis, key, "base"),
            head=_score(head_kpis, key, "PR"),
        )
        for key, label in QUALITY_KPIS
    ]


def _score(kpis: dict[str, Any], key: str, side: str) -> float:
    value = kpis.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"RepoWise {side} KPI {key!r} is not numeric")
    score = float(value)
    if not math.isfinite(score) or not 0 <= score <= 10:
        raise ValueError(f"RepoWise {side} KPI {key!r} is outside 0..10")
    return score


def render_markdown(
    comparisons: list[Comparison], base_sha: str, head_sha: str
) -> str:
    failed = [comparison for comparison in comparisons if comparison.regressed]
    status = "FAIL" if failed else "PASS"
    lines = [
        f"# RepoWise code-quality ratchet: {status}",
        "",
        "Every repository-level health score must stay equal to or improve over "
        "the pull-request base. Higher scores are better.",
        "",
        f"Base: `{base_sha}` · PR: `{head_sha}`",
        "",
        "| KPI | Base | PR | Delta | Result |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    for comparison in comparisons:
        result = "regressed" if comparison.regressed else "pass"
        lines.append(
            f"| {comparison.label} | {comparison.base:.2f} | "
            f"{comparison.head:.2f} | {comparison.delta:+.2f} | {result} |"
        )
    if failed:
        lines.extend(
            [
                "",
                "The PR decreases at least one RepoWise code-quality KPI.",
            ]
        )
    return "\n".join(lines) + "\n"


def render_risk_markdown(risk: dict[str, Any]) -> str:
    classification = _text(risk, "classification")
    priority = _text(risk, "review_priority")
    score = _number(risk, "score", maximum=10)
    percentile = _number(risk, "risk_percentile", maximum=100)
    sample_size = int(_number(risk, "baseline_sample_size"))
    features = risk.get("features")
    if not isinstance(features, dict):
        raise ValueError("RepoWise PR risk report has no feature object")

    lines = [
        "## PR change risk",
        "",
        "This advisory score analyzes the PR diff itself; it does not affect the "
        "quality-ratchet result.",
        "",
        "| Classification | Review priority | Percentile | Model score | Baseline |",
        "| --- | --- | ---: | ---: | ---: |",
        f"| {_cell(classification)} | {_cell(priority)} | {percentile:.1f} | "
        f"{score:.1f}/10 | {sample_size} commits |",
        "",
        "| Added | Deleted | Files | Directories | Subsystems | Entropy |",
        "| ---: | ---: | ---: | ---: | ---: | ---: |",
        f"| {int(_number(features, 'la'))} | {int(_number(features, 'ld'))} | "
        f"{int(_number(features, 'nf'))} | {int(_number(features, 'nd'))} | "
        f"{int(_number(features, 'ns'))} | {_number(features, 'entropy'):.2f} |",
    ]
    drivers = risk.get("drivers", [])
    if not isinstance(drivers, list):
        raise ValueError("RepoWise PR risk drivers are not a list")
    if drivers:
        lines.extend(
            [
                "",
                "### Main risk drivers",
                "",
                "| Driver | Value | Contribution |",
                "| --- | ---: | ---: |",
            ]
        )
        for driver in drivers[:6]:
            if not isinstance(driver, dict):
                raise ValueError("RepoWise PR risk driver is not an object")
            lines.append(
                f"| {_cell(_text(driver, 'label'))} | {_number(driver, 'value'):.2f} | "
                f"{_number(driver, 'contribution', minimum=None):+.3f} |"
            )
    return "\n".join(lines) + "\n"


def _number(
    values: dict[str, Any],
    key: str,
    minimum: float | None = 0,
    maximum: float | None = None,
) -> float:
    value = values.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"RepoWise field {key!r} is not numeric")
    number = float(value)
    if (
        not math.isfinite(number)
        or (minimum is not None and number < minimum)
        or (maximum is not None and number > maximum)
    ):
        raise ValueError(f"RepoWise field {key!r} is outside its expected range")
    return number


def _text(values: dict[str, Any], key: str) -> str:
    value = values.get(key)
    if not isinstance(value, str) or not value:
        raise ValueError(f"RepoWise field {key!r} is not text")
    return value


def _cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def _write_report(path: Path, markdown: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(markdown, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--head", required=True, type=Path)
    parser.add_argument("--risk", required=True, type=Path)
    parser.add_argument("--markdown", required=True, type=Path)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    args = parser.parse_args()

    try:
        comparisons = compare_health(
            load_health_report(args.base), load_health_report(args.head)
        )
        markdown = (
            render_markdown(comparisons, args.base_sha, args.head_sha).rstrip()
            + "\n\n"
            + render_risk_markdown(load_json_object(args.risk))
        )
    except (KeyError, ValueError) as error:
        markdown = f"# RepoWise code-quality ratchet: ERROR\n\n{error}\n"
        _write_report(args.markdown, markdown)
        print(error, file=sys.stderr)
        return 2

    _write_report(args.markdown, markdown)
    return 1 if any(comparison.regressed for comparison in comparisons) else 0


if __name__ == "__main__":
    raise SystemExit(main())
