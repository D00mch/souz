#!/usr/bin/env python3
"""Render detailed RepoWise refactoring and performance maintenance artifacts."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from repowise.core.analysis.health.perf.opportunities import (
    build_performance_opportunities,
)
from repowise.core.analysis.health.scoring import biomarker_dimension


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read RepoWise report {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"RepoWise report {path} is not a JSON object")
    return value


def performance_payload(health: dict[str, Any]) -> dict[str, Any]:
    findings = health.get("findings")
    if not isinstance(findings, list):
        raise ValueError("RepoWise health report has no findings list")
    performance_findings: list[dict[str, Any]] = []
    for finding in findings:
        if not isinstance(finding, dict):
            raise ValueError("RepoWise health finding is not an object")
        marker = finding.get("biomarker_type")
        if isinstance(marker, str) and biomarker_dimension(marker) == "performance":
            performance_findings.append({**finding, "dimension": "performance"})

    opportunities = [
        opportunity.as_dict()
        for opportunity in build_performance_opportunities(
            performance_findings, evidence_limit=8
        )
    ]
    contexts = Counter(opportunity["execution_context"] for opportunity in opportunities)
    return {
        "summary": {
            "total": len(opportunities),
            "production": contexts["production"],
            "tooling": contexts["tooling"],
            "test": contexts["test"],
            "with_fix": sum(opportunity["fix"] is not None for opportunity in opportunities),
        },
        "items": opportunities,
    }


def render_report(
    health: dict[str, Any],
    refactoring: dict[str, Any],
    performance: dict[str, Any],
    revision: str,
) -> str:
    kpis = _mapping(health, "kpis")
    targets = _list(refactoring, "targets")
    plans = _list(refactoring, "refactoring_plans")
    opportunities = _list(performance, "items")
    summary = _mapping(performance, "summary")
    lines = [
        "# RepoWise maintenance report",
        "",
        f"Revision: `{_inline(revision)}`",
        "",
        "This downloadable report contains deterministic refactoring plans and "
        "causal performance opportunities. It is advisory.",
        "",
        "## Code-health overview",
        "",
        "| Signal | Average | Hotspots |",
        "| --- | ---: | ---: |",
        f"| Defect health | {_score(kpis, 'average_health'):.2f} | "
        f"{_score(kpis, 'hotspot_health'):.2f} |",
        f"| Maintainability | {_score(kpis, 'maintainability_average'):.2f} | "
        f"{_score(kpis, 'maintainability_hotspot'):.2f} |",
        f"| Performance | {_score(kpis, 'performance_average'):.2f} | "
        f"{_score(kpis, 'performance_hotspot'):.2f} |",
        "",
        "## Ranked refactoring targets",
        "",
        "| File | Score | Impact | Effort | Primary issue |",
        "| --- | ---: | ---: | --- | --- |",
    ]
    for target in targets:
        target = _object(target, "refactoring target")
        lines.append(
            f"| {_cell(target.get('file_path'))} | {_float(target.get('score')):.2f} | "
            f"{_float(target.get('total_impact')):.2f} | {_cell(target.get('effort_bucket'))} | "
            f"{_cell(target.get('primary_reason'))} |"
        )
    if not targets:
        lines.append("| No targets | — | — | — | — |")

    lines.extend(["", "## Detailed refactoring plans", ""])
    if not plans:
        lines.append("No structured refactoring plans were produced.")
    for index, raw_plan in enumerate(plans, 1):
        plan = _object(raw_plan, "refactoring plan")
        plan_type = _label(plan.get("refactoring_type"))
        target = _inline(plan.get("target_symbol") or plan.get("file_path") or "target")
        location = _inline(plan.get("file_path") or "unknown")
        if plan.get("line_start") is not None:
            location += f":{plan['line_start']}"
        lines.extend(
            [
                f"### {index}. {plan_type}: `{target}`",
                "",
                f"- Location: `{location}`",
                f"- Confidence: {_inline(plan.get('confidence') or 'unknown')}",
                f"- Effort: {_inline(plan.get('effort_bucket') or 'unknown')}",
                f"- Source signal: `{_inline(plan.get('source_biomarker') or 'unknown')}`",
                f"- Rank score: {_float(plan.get('rank_score')):.2f}",
                "",
                "#### Proposed change",
                "",
                _json_block(plan.get("plan", {})),
                "",
                "#### Evidence and blast radius",
                "",
                _json_block(
                    {
                        "evidence": plan.get("evidence", {}),
                        "blast_radius": plan.get("blast_radius", {}),
                    }
                ),
            ]
        )
        validation = plan.get("validation")
        if isinstance(validation, dict):
            lines.extend(["", "#### Validation", ""])
            commands = validation.get("commands") or []
            tests = validation.get("tests") or []
            lines.append(
                "- Commands: "
                + (", ".join(f"`{_inline(command)}`" for command in commands) or "not inferred")
            )
            lines.append(
                "- Tests: "
                + (", ".join(f"`{_inline(test)}`" for test in tests) or "not inferred")
            )
        lines.append("")

    lines.extend(
        [
            "## Performance optimization",
            "",
            f"RepoWise grouped {int(_float(summary.get('total')))} findings into causal "
            f"opportunities: {int(_float(summary.get('production')))} production, "
            f"{int(_float(summary.get('tooling')))} tooling, and "
            f"{int(_float(summary.get('test')))} test. "
            f"{int(_float(summary.get('with_fix')))} have a deterministic optimization strategy.",
            "",
            "Production and tooling opportunities are detailed below. Test-only "
            "opportunities remain available in `performance-opportunities.json`.",
            "",
        ]
    )
    relevant = [
        _object(opportunity, "performance opportunity")
        for opportunity in opportunities
        if isinstance(opportunity, dict) and opportunity.get("execution_context") != "test"
    ]
    if not relevant:
        lines.append("No production or tooling performance opportunities were found.")
    for index, opportunity in enumerate(relevant, 1):
        intervention = opportunity.get("intervention_symbol") or opportunity.get("terminal_sink")
        title = _inline(intervention or opportunity.get("biomarker_type") or "opportunity")
        lines.extend(
            [
                f"### {index}. `{title}`",
                "",
                f"- Signal: `{_inline(opportunity.get('biomarker_type'))}`",
                f"- Context: {_inline(opportunity.get('execution_context'))}",
                f"- Boundary: {_inline(opportunity.get('boundary_kind') or 'local computation')}",
                f"- Confidence: {_inline(opportunity.get('confidence'))}",
                f"- Affected call sites: {int(_float(opportunity.get('affected_call_sites_total')))}",
                f"- Rank score: {int(_float(opportunity.get('rank_score')))}",
                "",
            ]
        )
        fix = opportunity.get("fix")
        if isinstance(fix, dict):
            lines.extend(
                [
                    f"Optimization: **{_label(fix.get('strategy'))}** "
                    f"({_inline(fix.get('safety'))}).",
                    "",
                    _inline(fix.get("rationale")),
                    "",
                ]
            )
        else:
            lines.extend(
                [
                    "Optimization: investigate the proven repeated-cost path; RepoWise "
                    "could not infer one safe intervention without guessing.",
                    "",
                ]
            )
        for evidence in opportunity.get("evidence", []):
            evidence = _object(evidence, "performance evidence")
            symbol = evidence.get("function_name") or "file scope"
            lines.append(
                f"- `{_inline(evidence.get('file_path'))}::{_inline(symbol)}` — "
                f"{_inline(evidence.get('reason'))}"
            )
            path = evidence.get("path") or []
            if path:
                lines.append("  - Call path: " + " → ".join(f"`{_inline(node)}`" for node in path))
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def render_summary(
    refactoring: dict[str, Any], performance: dict[str, Any], revision: str
) -> str:
    targets = _list(refactoring, "targets")[:5]
    opportunities = [
        item
        for item in _list(performance, "items")
        if isinstance(item, dict) and item.get("execution_context") != "test"
    ][:5]
    lines = [
        "# RepoWise maintenance summary",
        "",
        f"Revision: `{_inline(revision)}`",
        "",
        "The downloadable artifact contains full structured plans, evidence, "
        "blast radius, validation, and performance opportunities.",
        "",
        "## Top refactoring targets",
        "",
    ]
    lines.extend(
        f"- `{_inline(target.get('file_path'))}` — {_inline(target.get('primary_reason'))}"
        for target in targets
        if isinstance(target, dict)
    )
    if not targets:
        lines.append("- None")
    lines.extend(["", "## Top performance opportunities", ""])
    for opportunity in opportunities:
        fix = opportunity.get("fix")
        strategy = _label(fix.get("strategy")) if isinstance(fix, dict) else "Investigation needed"
        target = opportunity.get("intervention_symbol") or opportunity.get("terminal_sink")
        lines.append(f"- **{strategy}** — `{_inline(target or opportunity.get('biomarker_type'))}`")
    if not opportunities:
        lines.append("- None")
    return "\n".join(lines) + "\n"


def _mapping(values: dict[str, Any], key: str) -> dict[str, Any]:
    value = values.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"RepoWise field {key!r} is not an object")
    return value


def _list(values: dict[str, Any], key: str) -> list[Any]:
    value = values.get(key)
    if not isinstance(value, list):
        raise ValueError(f"RepoWise field {key!r} is not a list")
    return value


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"RepoWise {label} is not an object")
    return value


def _score(values: dict[str, Any], key: str) -> float:
    score = _float(values.get(key))
    if not 0 <= score <= 10:
        raise ValueError(f"RepoWise score {key!r} is outside 0..10")
    return score


def _float(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("RepoWise numeric field is invalid")
    return float(value)


def _label(value: Any) -> str:
    label = _inline(value or "unknown").replace("_", " ").capitalize()
    return label.replace(" io", " I/O").replace(" db", " DB").replace(" api", " API")


def _inline(value: Any) -> str:
    return str(value or "").replace("`", "'").replace("\n", " ")


def _cell(value: Any) -> str:
    return _inline(value).replace("|", "\\|")


def _json_block(value: Any) -> str:
    return "```json\n" + json.dumps(_without_snippets(value), indent=2, ensure_ascii=False) + "\n```"


def _without_snippets(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: _without_snippets(item)
            for key, item in value.items()
            if key not in {"snippet", "snippet_start_line", "snippet_truncated"}
        }
    if isinstance(value, list):
        return [_without_snippets(item) for item in value]
    return value


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--health", required=True, type=Path)
    parser.add_argument("--refactoring", required=True, type=Path)
    parser.add_argument("--markdown", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--performance-json", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()

    health = load_json_object(args.health)
    refactoring = load_json_object(args.refactoring)
    performance = performance_payload(health)
    _write(
        args.performance_json,
        json.dumps(performance, indent=2, ensure_ascii=False) + "\n",
    )
    _write(
        args.markdown,
        render_report(health, refactoring, performance, args.revision),
    )
    _write(args.summary, render_summary(refactoring, performance, args.revision))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
