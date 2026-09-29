"""Allowlisted quality-analysis recipes composed from mature numerical libraries."""

from __future__ import annotations

import math
from typing import Any, Callable, Mapping, Sequence

import numpy as np
import pandas as pd

from .contracts import AnalysisContractError, AnalysisSpec
from .operators import (
    json_safe,
    linear_slope,
    numeric_series,
    records,
    require_columns,
    require_non_empty_list,
)


def execute_analysis(
    spec: AnalysisSpec | Mapping[str, Any], rows: Sequence[Mapping[str, Any]]
) -> dict[str, Any]:
    resolved_spec = (
        spec if isinstance(spec, AnalysisSpec) else AnalysisSpec.from_mapping(spec)
    )

    if resolved_spec.mode == "legacy":
        return {
            "status": "legacy_required",
            "contract_version": resolved_spec.contract_version,
            "reason": resolved_spec.reason,
        }

    if not isinstance(rows, Sequence) or isinstance(rows, (str, bytes)):
        raise AnalysisContractError("rows must be a list of objects")

    frame = pd.DataFrame(list(rows))
    handler = _RECIPES[resolved_spec.recipe_id]
    return json_safe(handler(resolved_spec, frame))


def _tabular_aggregate(spec: AnalysisSpec, frame: pd.DataFrame) -> dict[str, Any]:
    params = spec.parameters
    _reject_unknown_parameters(
        params,
        {"group_by", "aggregations", "derived", "sort", "limit", "trend"},
        spec.recipe_id,
    )
    group_by = require_non_empty_list(params.get("group_by"), "group_by")
    aggregations = params.get("aggregations")
    if not isinstance(aggregations, list) or not aggregations:
        raise AnalysisContractError("aggregations must be a non-empty list")

    require_columns(frame, group_by)
    working = frame.copy()
    working["_row_count"] = 1
    named_aggregations: dict[str, pd.NamedAgg] = {}

    allowed_operations = {"count", "sum", "mean", "min", "max", "nunique"}
    for definition in aggregations:
        if not isinstance(definition, Mapping):
            raise AnalysisContractError("each aggregation must be an object")
        name = definition.get("name")
        operation = definition.get("operation")
        column = definition.get("column")
        if not isinstance(name, str) or not name:
            raise AnalysisContractError("aggregation name is required")
        if name in named_aggregations:
            raise AnalysisContractError(f"duplicate aggregation name: {name}")
        if operation not in allowed_operations:
            raise AnalysisContractError(f"unsupported aggregate operation: {operation}")

        if operation == "count" and column is None:
            column = "_row_count"
            operation = "sum"
        if not isinstance(column, str) or not column:
            raise AnalysisContractError(f"aggregation {name} requires column")
        require_columns(working, [column])
        named_aggregations[name] = pd.NamedAgg(column=column, aggfunc=operation)

    result = (
        working.groupby(group_by, dropna=False, sort=False)
        .agg(**named_aggregations)
        .reset_index()
    )

    _apply_ratio_fields(result, params.get("derived", []))
    _apply_sort_and_limit(result, params)

    output: dict[str, Any] = {
        "status": "success",
        "recipe_id": spec.recipe_id,
        "recipe_version": spec.recipe_version,
        "row_count": len(result),
        "rows": records(result),
    }
    trend = _build_trend(result, params.get("trend"))
    if trend is not None:
        output["trend"] = trend
    return output


def _apply_ratio_fields(result: pd.DataFrame, definitions: Any) -> None:
    if definitions is None:
        return
    if not isinstance(definitions, list):
        raise AnalysisContractError("derived must be a list")

    for definition in definitions:
        if not isinstance(definition, Mapping):
            raise AnalysisContractError("each derived metric must be an object")
        operation = definition.get("operation")
        if operation not in {"ratio", "share_of_total"}:
            raise AnalysisContractError(
                "derived metric operation must be ratio or share_of_total"
            )
        name = definition.get("name")
        numerator = definition.get("numerator")
        scale = definition.get("scale", 1)
        if not all(isinstance(item, str) and item for item in (name, numerator)):
            raise AnalysisContractError("derived metric requires name and numerator")
        if numerator not in result:
            raise AnalysisContractError(
                f"derived metric {name} references missing numerator"
            )
        if not isinstance(scale, (int, float)):
            raise AnalysisContractError(f"derived metric {name} scale must be numeric")

        numerator_values = numeric_series(result[numerator], numerator)
        if operation == "ratio":
            denominator = definition.get("denominator")
            if not isinstance(denominator, str) or denominator not in result:
                raise AnalysisContractError(
                    f"ratio {name} references missing denominator"
                )
            denominator_values = numeric_series(result[denominator], denominator)
            result[name] = np.where(
                denominator_values != 0,
                numerator_values / denominator_values * float(scale),
                np.nan,
            )
        else:
            total = float(numerator_values.sum())
            result[name] = (
                numerator_values / total * float(scale) if total != 0 else np.nan
            )


def _apply_sort_and_limit(result: pd.DataFrame, params: Mapping[str, Any]) -> None:
    definitions = params.get("sort", [])
    if definitions:
        if not isinstance(definitions, list):
            raise AnalysisContractError("sort must be a list")
        columns: list[str] = []
        directions: list[bool] = []
        for definition in definitions:
            if not isinstance(definition, Mapping):
                raise AnalysisContractError("each sort definition must be an object")
            column = definition.get("column")
            direction = definition.get("direction", "asc")
            if column not in result:
                raise AnalysisContractError(f"sort references missing column: {column}")
            if direction not in {"asc", "desc"}:
                raise AnalysisContractError("sort direction must be asc or desc")
            columns.append(column)
            directions.append(direction == "asc")
        result.sort_values(columns, ascending=directions, inplace=True, kind="stable")

    limit = params.get("limit")
    if limit is not None:
        if not isinstance(limit, int) or limit < 1:
            raise AnalysisContractError("limit must be a positive integer")
        result.drop(result.index[limit:], inplace=True)

    result.reset_index(drop=True, inplace=True)


def _build_trend(result: pd.DataFrame, definition: Any) -> dict[str, Any] | None:
    if definition is None:
        return None
    if not isinstance(definition, Mapping):
        raise AnalysisContractError("trend must be an object")

    x_column = definition.get("x")
    y_column = definition.get("y")
    if x_column not in result or y_column not in result:
        raise AnalysisContractError("trend references a missing result column")
    if len(result) < 2:
        return {
            "x": x_column,
            "y": y_column,
            "slope": None,
            "direction": "insufficient_data",
        }

    ordered = result.sort_values(x_column, kind="stable")
    slope = linear_slope(range(len(ordered)), ordered[y_column])
    if slope is None:
        direction = "insufficient_data"
    elif np.isclose(slope, 0.0):
        direction = "flat"
    elif slope > 0:
        direction = "rising"
    else:
        direction = "falling"
    return {"x": x_column, "y": y_column, "slope": slope, "direction": direction}


def _dimension_variation(spec: AnalysisSpec, frame: pd.DataFrame) -> dict[str, Any]:
    params = spec.parameters
    _reject_unknown_parameters(
        params,
        {
            "group_by",
            "sample_column",
            "value_column",
            "lower_limit_column",
            "upper_limit_column",
            "rules",
        },
        spec.recipe_id,
    )
    group_by = require_non_empty_list(params.get("group_by"), "group_by")
    sample_column = _required_name(params, "sample_column")
    value_column = _required_name(params, "value_column")
    lower_column = _required_name(params, "lower_limit_column")
    upper_column = _required_name(params, "upper_limit_column")
    rules = _variation_rules(params.get("rules"))

    require_columns(
        frame,
        [*group_by, sample_column, value_column, lower_column, upper_column],
    )

    working = frame.copy()
    for column in (value_column, lower_column, upper_column):
        working[column] = pd.to_numeric(working[column], errors="coerce")
    working = working.dropna(
        subset=[*group_by, sample_column, value_column, lower_column, upper_column]
    )
    working = working[working[upper_column] > working[lower_column]]

    if working.empty:
        return {
            "status": "success",
            "recipe_id": spec.recipe_id,
            "recipe_version": spec.recipe_version,
            "analysis_summary": {
                "total_points_analyzed": 0,
                "candidate_points_count": 0,
                "high_variation_points_count": 0,
            },
            "high_variation_points": [],
        }

    summaries: list[dict[str, Any]] = []
    grouped = working.groupby(group_by, dropna=False, sort=False)
    for group_values, group in grouped:
        values = group_values if isinstance(group_values, tuple) else (group_values,)
        lower_values = group[lower_column].unique()
        upper_values = group[upper_column].unique()
        if len(lower_values) != 1 or len(upper_values) != 1:
            raise AnalysisContractError(
                "dimension_variation requires one tolerance range per point group"
            )

        count = int(group[value_column].count())
        minimum = float(group[value_column].min())
        maximum = float(group[value_column].max())
        average = float(group[value_column].mean())
        std = float(group[value_column].std(ddof=1)) if count > 1 else 0.0
        lower_limit = float(lower_values[0])
        upper_limit = float(upper_values[0])
        tolerance_width = upper_limit - lower_limit
        range_value = maximum - minimum
        range_ratio = range_value / tolerance_width

        record = dict(zip(group_by, values))
        record.update(
            {
                "sample_count": count,
                "min_value": minimum,
                "max_value": maximum,
                "avg": average,
                "std": std,
                "lower_limit": lower_limit,
                "upper_limit": upper_limit,
                "tolerance_width": tolerance_width,
                "range": range_value,
                "range_ratio": range_ratio,
                "_group_rows": group,
            }
        )
        summaries.append(record)

    summary = pd.DataFrame(summaries)
    valid = summary[summary["sample_count"] >= 2].copy()
    if valid.empty:
        return {
            "status": "success",
            "recipe_id": spec.recipe_id,
            "recipe_version": spec.recipe_version,
            "analysis_summary": {
                "total_points_analyzed": 0,
                "candidate_points_count": 0,
                "high_variation_points_count": 0,
            },
            "high_variation_points": [],
        }

    valid.sort_values("range", ascending=False, inplace=True, kind="stable")
    valid.reset_index(drop=True, inplace=True)
    candidate_count = max(1, math.ceil(len(valid) * rules["candidate_fraction"]))
    valid["is_candidate"] = valid.index < candidate_count

    high_variation_points: list[dict[str, Any]] = []
    all_points: list[dict[str, Any]] = []
    for _, point in valid.iterrows():
        result = _analyze_variation_point(point, sample_column, value_column, rules)
        all_points.append(result)
        if result["is_high_variation"]:
            high_variation_points.append(result)

    high_variation_points.sort(key=lambda item: item["range_ratio"], reverse=True)
    return {
        "status": "success",
        "recipe_id": spec.recipe_id,
        "recipe_version": spec.recipe_version,
        "analysis_summary": {
            "total_points_analyzed": len(valid),
            "candidate_points_count": candidate_count,
            "high_variation_points_count": len(high_variation_points),
        },
        "high_variation_points": high_variation_points,
        "all_points": all_points,
    }


def _analyze_variation_point(
    point: pd.Series,
    sample_column: str,
    value_column: str,
    rules: Mapping[str, float | int],
) -> dict[str, Any]:
    group = point["_group_rows"].copy()
    order = pd.to_numeric(group[sample_column], errors="coerce")
    group["_analysis_order"] = (
        order if order.notna().all() else np.arange(1, len(group) + 1)
    )
    group.sort_values("_analysis_order", inplace=True, kind="stable")

    slope = linear_slope(group["_analysis_order"], group[value_column])
    midpoint = (float(point["upper_limit"]) + float(point["lower_limit"])) / 2
    if slope is None:
        trend = "insufficient_data"
    elif slope > 0 and float(point["avg"]) > midpoint and not np.isclose(
        float(point["avg"]), midpoint
    ):
        trend = "rising_toward_upper"
    elif slope < 0 and float(point["avg"]) < midpoint and not np.isclose(
        float(point["avg"]), midpoint
    ):
        trend = "falling_toward_lower"
    elif abs(slope) > (
        float(point["tolerance_width"])
        / int(point["sample_count"])
        * float(rules["trend_slope_factor"])
    ):
        trend = "significant_linear_trend"
    else:
        trend = "no_clear_trend"

    cpk: float | None = None
    ppk: float | None = None
    reasons: list[str] = []
    if (
        int(point["sample_count"]) >= int(rules["minimum_cpk_samples"])
        and float(point["std"]) > 0
    ):
        cpk = min(
            (float(point["upper_limit"]) - float(point["avg"]))
            / (3 * float(point["std"])),
            (float(point["avg"]) - float(point["lower_limit"]))
            / (3 * float(point["std"])),
        )
        population_std = float(group[value_column].std(ddof=0))
        if population_std > 0:
            ppk = min(
                (float(point["upper_limit"]) - float(point["avg"]))
                / (3 * population_std),
                (float(point["avg"]) - float(point["lower_limit"]))
                / (3 * population_std),
            )
        if cpk < float(rules["cpk_threshold"]):
            reasons.append("cpk_below_threshold")

    if float(point["range_ratio"]) >= float(rules["range_ratio_high"]):
        reasons.append("range_ratio_high")
    elif (
        float(point["range_ratio"]) >= float(rules["range_ratio_medium"])
        and float(point["std"])
        > float(point["tolerance_width"]) / float(rules["std_divisor"])
    ):
        reasons.append("range_ratio_and_std_high")
    if bool(point["is_candidate"]):
        reasons.append("range_rank_top_fraction")

    result = {
        key: value
        for key, value in point.items()
        if key not in {"_group_rows", "is_candidate"}
    }
    result.update(
        {
            "cpk": cpk,
            "ppk": ppk,
            "is_sample_reach_minimum_cpk": int(point["sample_count"])
            >= int(rules["minimum_cpk_samples"]),
            "trend_slope": slope,
            "trend": trend,
            "variation_reasons": reasons,
            "is_high_variation": bool(reasons),
        }
    )
    return json_safe(result)


def _required_name(params: Mapping[str, Any], field_name: str) -> str:
    value = params.get(field_name)
    if not isinstance(value, str) or not value:
        raise AnalysisContractError(f"{field_name} is required")
    return value


def _variation_rules(value: Any) -> dict[str, float | int]:
    if not isinstance(value, Mapping):
        raise AnalysisContractError("dimension_variation requires rules")

    required = {
        "candidate_fraction": float,
        "range_ratio_high": float,
        "range_ratio_medium": float,
        "std_divisor": float,
        "minimum_cpk_samples": int,
        "cpk_threshold": float,
        "trend_slope_factor": float,
    }
    unexpected = set(value) - set(required)
    if unexpected:
        raise AnalysisContractError(
            "rules contain unsupported fields: " + ", ".join(sorted(unexpected))
        )
    rules: dict[str, float | int] = {}
    for name, expected_type in required.items():
        item = value.get(name)
        if expected_type is int:
            if not isinstance(item, int) or item < 2:
                raise AnalysisContractError(f"rules.{name} must be an integer >= 2")
        elif not isinstance(item, (int, float)) or float(item) <= 0:
            raise AnalysisContractError(f"rules.{name} must be a positive number")
        rules[name] = item

    if not 0 < float(rules["candidate_fraction"]) <= 1:
        raise AnalysisContractError("rules.candidate_fraction must be in (0, 1]")
    return rules


def _reject_unknown_parameters(
    parameters: Mapping[str, Any], allowed: set[str], recipe_id: str | None
) -> None:
    unexpected = set(parameters) - allowed
    if unexpected:
        raise AnalysisContractError(
            f"{recipe_id} contains unsupported parameters: "
            + ", ".join(sorted(unexpected))
        )


_RECIPES: dict[str, Callable[[AnalysisSpec, pd.DataFrame], dict[str, Any]]] = {
    "tabular_aggregate_v1": _tabular_aggregate,
    "dimension_variation_v1": _dimension_variation,
}
