"""Synthetic data matching the observed point-variation shape without business identifiers."""

from __future__ import annotations

from typing import Any


TOP_POINT_RANGES = {
    "P001": 0.83,
    "P002": 0.78,
    "P003": 0.75,
    "P004": 0.73,
    "P005": 0.72,
    "P006": 0.68,
    "P007": 0.67,
}


def dimension_variation_rows(
    point_count: int = 42, samples_per_point: int = 10
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for point_index in range(1, point_count + 1):
        point = f"P{point_index:03d}"
        spread = TOP_POINT_RANGES.get(point, 0.22 + point_index / 1000)
        lower_limit, upper_limit = -0.75, 0.75
        start = -spread / 2
        step = spread / (samples_per_point - 1)
        for sample_no in range(1, samples_per_point + 1):
            rows.append(
                {
                    "part_name": "synthetic_part",
                    "measurement_point": point,
                    "sample_no": sample_no,
                    "value": round(start + step * (sample_no - 1), 4),
                    "lower_limit": lower_limit,
                    "upper_limit": upper_limit,
                }
            )
    return rows


def paint_quality_rows() -> list[dict[str, Any]]:
    return [
        {
            "week": "2026-W23",
            "defect_name": "particle",
            "inspection_count": 100,
            "qualified_count": 95,
            "defect_count": 11,
        },
        {
            "week": "2026-W23",
            "defect_name": "scratch",
            "inspection_count": 100,
            "qualified_count": 95,
            "defect_count": 7,
        },
        {
            "week": "2026-W24",
            "defect_name": "particle",
            "inspection_count": 120,
            "qualified_count": 114,
            "defect_count": 9,
        },
        {
            "week": "2026-W24",
            "defect_name": "scratch",
            "inspection_count": 120,
            "qualified_count": 114,
            "defect_count": 5,
        },
        {
            "week": "2026-W25",
            "defect_name": "color",
            "inspection_count": 80,
            "qualified_count": 76,
            "defect_count": 4,
        },
    ]


def weekly_yield_rows() -> list[dict[str, Any]]:
    return [
        {
            "week": "2026-W23",
            "inspection_count": 100,
            "qualified_count": 95,
        },
        {
            "week": "2026-W24",
            "inspection_count": 120,
            "qualified_count": 116,
        },
        {
            "week": "2026-W25",
            "inspection_count": 80,
            "qualified_count": 78,
        },
    ]


def dimension_variation_spec() -> dict[str, Any]:
    return {
        "contract_version": "1.0",
        "mode": "recipe",
        "recipe_id": "dimension_variation_v1",
        "recipe_version": "1.0",
        "parameters": {
            "group_by": ["part_name", "measurement_point"],
            "sample_column": "sample_no",
            "value_column": "value",
            "lower_limit_column": "lower_limit",
            "upper_limit_column": "upper_limit",
            "rules": {
                "candidate_fraction": 0.15,
                "range_ratio_high": 0.5,
                "range_ratio_medium": 0.3,
                "std_divisor": 6,
                "minimum_cpk_samples": 125,
                "cpk_threshold": 1.33,
                "trend_slope_factor": 0.5,
            },
        },
        "reason": "Synthetic reproduction of point-variation analysis.",
    }


def weekly_yield_spec() -> dict[str, Any]:
    return {
        "contract_version": "1.0",
        "mode": "recipe",
        "recipe_id": "tabular_aggregate_v1",
        "recipe_version": "1.0",
        "parameters": {
            "group_by": ["week"],
            "aggregations": [
                {
                    "name": "inspection_total",
                    "column": "inspection_count",
                    "operation": "sum",
                },
                {
                    "name": "qualified_total",
                    "column": "qualified_count",
                    "operation": "sum",
                },
            ],
            "derived": [
                {
                    "name": "yield_rate",
                    "operation": "ratio",
                    "numerator": "qualified_total",
                    "denominator": "inspection_total",
                    "scale": 100,
                }
            ],
            "sort": [{"column": "week", "direction": "asc"}],
            "trend": {"x": "week", "y": "yield_rate"},
        },
        "reason": "Weekly yield-rate aggregation.",
    }


def defect_top_spec() -> dict[str, Any]:
    return {
        "contract_version": "1.0",
        "mode": "recipe",
        "recipe_id": "tabular_aggregate_v1",
        "recipe_version": "1.0",
        "parameters": {
            "group_by": ["defect_name"],
            "aggregations": [
                {
                    "name": "defect_total",
                    "column": "defect_count",
                    "operation": "sum",
                }
            ],
            "derived": [
                {
                    "name": "defect_share",
                    "operation": "share_of_total",
                    "numerator": "defect_total",
                    "scale": 100,
                }
            ],
            "sort": [{"column": "defect_total", "direction": "desc"}],
            "limit": 3,
        },
        "reason": "Top defect count aggregation.",
    }
