"""Fixed semantic contracts for the five approved spray-analysis POC cases."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from .contracts import AnalysisContractError, AnalysisSpec


@dataclass(frozen=True)
class SprayCase:
    case_id: str
    request_file: str
    template_id: str
    source_tables: tuple[str, ...]
    metric_definition: str
    expected_parameters: dict[str, Any]
    source_metric_column: str | None = None


def _spec_parameters(
    group_by: list[str],
    aggregations: list[dict[str, Any]],
    *,
    derived: list[dict[str, Any]] | None = None,
    sort: list[dict[str, str]] | None = None,
    limit: int | None = None,
    trend: dict[str, str] | None = None,
) -> dict[str, Any]:
    parameters: dict[str, Any] = {
        "group_by": group_by,
        "aggregations": aggregations,
    }
    if derived:
        parameters["derived"] = derived
    if sort:
        parameters["sort"] = sort
    if limit is not None:
        parameters["limit"] = limit
    if trend:
        parameters["trend"] = trend
    return parameters


SPRAY_CASES: dict[str, SprayCase] = {
    "weekly_first_pass_rate": SprayCase(
        case_id="weekly_first_pass_rate",
        request_file="opencode_spray_weekly_first_pass_request.txt",
        template_id="spray_weekly_first_pass_source_v1",
        source_tables=("paint_product_shift_stats",),
        metric_definition="每周一次合格率 = SUM(first_pass_count) / SUM(check_total) * 100。",
        expected_parameters=_spec_parameters(
            ["week"],
            [
                {"name": "check_total", "column": "check_total", "operation": "sum"},
                {
                    "name": "first_pass_count",
                    "column": "first_pass_count",
                    "operation": "sum",
                },
            ],
            derived=[
                {
                    "name": "first_pass_rate",
                    "operation": "ratio",
                    "numerator": "first_pass_count",
                    "denominator": "check_total",
                    "scale": 100,
                }
            ],
            sort=[{"column": "week", "direction": "asc"}],
            trend={"x": "week", "y": "first_pass_rate"},
        ),
    ),
    "defect_top3_share": SprayCase(
        case_id="defect_top3_share",
        request_file="opencode_spray_defect_top3_request.txt",
        template_id="spray_defect_top_source_v1",
        source_tables=("paint_defect_detail", "paint_defect_dictionary"),
        metric_definition="缺陷占比 = 某具体缺陷 SUM(defect_count) / 所有具体缺陷 SUM(defect_count) * 100。",
        expected_parameters=_spec_parameters(
            ["defect_name"],
            [{"name": "defect_total", "column": "defect_count", "operation": "sum"}],
            derived=[
                {
                    "name": "defect_share",
                    "operation": "share_of_total",
                    "numerator": "defect_total",
                    "scale": 100,
                }
            ],
            sort=[{"column": "defect_total", "direction": "desc"}],
            limit=3,
        ),
    ),
    "three_defect_categories": SprayCase(
        case_id="three_defect_categories",
        request_file="opencode_spray_three_categories_request.txt",
        template_id="spray_three_defect_categories_source_v1",
        source_tables=("paint_product_shift_stats",),
        metric_definition="按批次汇总塑件、喷房、设备环境三个来源字段中的缺陷数量。",
        expected_parameters=_spec_parameters(
            ["defect_category"],
            [{"name": "defect_total", "column": "defect_count", "operation": "sum"}],
            sort=[{"column": "defect_total", "direction": "desc"}],
        ),
    ),
    "shift_defect_rate": SprayCase(
        case_id="shift_defect_rate",
        request_file="opencode_spray_shift_defect_rate_request.txt",
        template_id="spray_shift_defect_rate_source_v1",
        source_tables=("paint_product_shift_stats",),
        metric_definition="班次不良率 = SUM(ng_total) / SUM(check_total) * 100。",
        expected_parameters=_spec_parameters(
            ["shift_name"],
            [
                {"name": "ng_total", "column": "ng_total", "operation": "sum"},
                {"name": "check_total", "column": "check_total", "operation": "sum"},
            ],
            derived=[
                {
                    "name": "defect_rate",
                    "operation": "ratio",
                    "numerator": "ng_total",
                    "denominator": "check_total",
                    "scale": 100,
                }
            ],
            sort=[{"column": "shift_name", "direction": "asc"}],
        ),
    ),
    "source_overall_pass_rate": SprayCase(
        case_id="source_overall_pass_rate",
        request_file="opencode_spray_overall_pass_rate_request.txt",
        template_id="spray_overall_pass_rate_source_v1",
        source_tables=("paint_product_shift_stats",),
        metric_definition="使用 Excel 来源拥有的 overall_pass_rate 原值；同一范围出现多个值时不汇总。",
        expected_parameters=_spec_parameters(
            ["overall_pass_rate"],
            [
                {
                    "name": "source_batch_count",
                    "column": "source_batch_count",
                    "operation": "sum",
                }
            ],
            sort=[{"column": "overall_pass_rate", "direction": "asc"}],
        ),
        source_metric_column="overall_pass_rate",
    ),
}


def get_spray_case(case_id: str) -> SprayCase:
    try:
        return SPRAY_CASES[case_id]
    except KeyError as exc:
        raise AnalysisContractError(f"unknown spray POC case: {case_id}") from exc


def request_path(root: Path, case: SprayCase) -> Path:
    return root / "fixtures" / case.request_file


def validate_spray_case_spec(case: SprayCase, spec: AnalysisSpec) -> None:
    if spec.mode != "recipe" or spec.recipe_id != "tabular_aggregate_v1":
        raise AnalysisContractError(
            f"{case.case_id} must select tabular_aggregate_v1"
        )
    if spec.recipe_version != "1.0":
        raise AnalysisContractError(f"{case.case_id} must select recipe version 1.0")
    actual_parameters = dict(spec.parameters)
    for optional_field in ("derived", "sort"):
        if actual_parameters.get(optional_field) == []:
            actual_parameters.pop(optional_field)
    if actual_parameters != case.expected_parameters:
        raise AnalysisContractError(
            f"{case.case_id} returned an unapproved aggregation contract"
        )


def source_metric_values(case: SprayCase, rows: list[Mapping[str, Any]]) -> list[float]:
    if not case.source_metric_column:
        return []
    values: set[float] = set()
    for row in rows:
        value = row.get(case.source_metric_column)
        if value is None:
            raise AnalysisContractError(
                f"{case.case_id} source metric is missing from a returned row"
            )
        try:
            values.add(float(value))
        except (TypeError, ValueError) as exc:
            raise AnalysisContractError(
                f"{case.case_id} source metric is non-numeric"
            ) from exc
    return sorted(values)
