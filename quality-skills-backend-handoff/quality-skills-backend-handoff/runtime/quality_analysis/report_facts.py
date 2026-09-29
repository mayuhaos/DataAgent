"""Deterministic checks that an analysis result contains report-ready facts."""

from __future__ import annotations

from typing import Any, Mapping

from .spray_cases import SprayCase


def assess_spray_report_facts(
    case: SprayCase,
    analysis: Mapping[str, Any] | None,
    *,
    factory_id: int,
    start_date: str,
    end_date: str,
    source_metric_conflict: list[float] | None = None,
) -> dict[str, Any]:
    scope = {
        "factory_id": factory_id,
        "start_date": start_date,
        "end_date_exclusive": end_date,
    }
    base = {
        "case_id": case.case_id,
        "source_tables": list(case.source_tables),
        "scope": scope,
        "metric_definition": case.metric_definition,
    }
    if source_metric_conflict is not None:
        return {
            **base,
            "status": "blocked",
            "reason": "source_metric_conflict",
            "distinct_source_metric_count": len(source_metric_conflict),
            "source_metric_min": min(source_metric_conflict),
            "source_metric_max": max(source_metric_conflict),
        }
    if not analysis or analysis.get("status") != "success":
        return {**base, "status": "blocked", "reason": "analysis_not_successful"}

    rows = analysis.get("rows")
    if not isinstance(rows, list) or not rows:
        return {**base, "status": "blocked", "reason": "no_result_rows"}

    reasons: list[str] = []
    if case.case_id == "weekly_first_pass_rate" and len(rows) < 2:
        reasons.append("insufficient_weekly_points")
    if case.case_id == "three_defect_categories":
        categories = {row.get("defect_category") for row in rows}
        expected = {"塑件缺陷", "喷房缺陷", "设备环境"}
        if categories != expected:
            reasons.append("missing_required_defect_category")
    if case.case_id == "shift_defect_rate":
        shifts = {row.get("shift_name") for row in rows}
        expected = {"白班", "夜班"}
        if shifts != expected:
            reasons.append("day_night_shift_mapping_unverified")

    return {
        **base,
        "status": "reportable" if not reasons else "needs_review",
        "reasons": reasons,
        "result_row_count": len(rows),
    }


def assess_dimension_report_facts(
    analysis: Mapping[str, Any],
    *,
    factory_id: int,
    measurement_date: str,
    part_name: str,
) -> dict[str, Any]:
    summary = analysis.get("analysis_summary")
    points = analysis.get("high_variation_points")
    if not isinstance(summary, Mapping) or not isinstance(points, list) or not points:
        return {
            "status": "blocked",
            "reason": "dimension_analysis_is_incomplete",
        }
    return {
        "status": "reportable",
        "source_tables": [
            "inspection_report",
            "dimension_measurement",
            "tolerance_catalog",
        ],
        "scope": {
            "factory_id": factory_id,
            "measurement_date": measurement_date,
            "part_name": part_name,
        },
        "metric_definition": "按点位计算极差、标准差、公差带占比；样本数达到125时才计算 Cpk/Ppk。",
        "total_points_analyzed": summary.get("total_points_analyzed"),
        "high_variation_points_count": summary.get("high_variation_points_count"),
        "result_row_count": len(points),
    }
