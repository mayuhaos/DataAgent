"""Build report-safe fact packs from verified quality-analysis results."""

from __future__ import annotations

from typing import Any, Mapping

from quality_analysis.spray_cases import SprayCase

from .contracts import REPORT_CONTRACT_VERSION, ChartSpec, ReportFacts


REPORT_PRESENTATIONS: dict[str, dict[str, Any]] = {
    "weekly_first_pass_rate": {
        "question": "2026年6月按周一次合格率趋势",
        "title": "2026年6月喷涂一次合格率周趋势",
        "chart": {
            "chart_type": "line",
            "dataset_id": "analysis_rows",
            "x_field": "week",
            "y_field": "first_pass_rate",
            "title": "一次合格率周趋势",
        },
    },
    "defect_top3_share": {
        "question": "2026年6月喷涂表格的缺陷 TOP3 数量分别占总缺陷数量比是多少",
        "title": "2026年6月喷涂缺陷 Top3",
        "chart": {
            "chart_type": "bar",
            "dataset_id": "analysis_rows",
            "x_field": "defect_name",
            "y_field": "defect_total",
            "title": "缺陷 Top3 数量",
        },
    },
    "three_defect_categories": {
        "question": "对比2026年6月喷涂表格的塑件缺陷、喷房缺陷和设备环境三类不良数",
        "title": "2026年6月喷涂三类缺陷对比",
        "chart": {
            "chart_type": "bar",
            "dataset_id": "analysis_rows",
            "x_field": "defect_category",
            "y_field": "defect_total",
            "title": "三类缺陷数量对比",
        },
    },
    "shift_defect_rate": {
        "question": "基于2026年6月喷涂报表的白夜班缺陷不良率比对",
        "title": "2026年6月喷涂白夜班不良率对比",
        "chart": {
            "chart_type": "bar",
            "dataset_id": "analysis_rows",
            "x_field": "shift_name",
            "y_field": "defect_rate",
            "title": "白夜班不良率对比",
        },
    },
    "source_overall_pass_rate": {
        "question": "2026年6月喷涂报表的产品总合格率",
        "title": "2026年6月喷涂产品总合格率",
    },
}


def build_spray_report_facts(
    case: SprayCase,
    analysis: Mapping[str, Any] | None,
    reportability: Mapping[str, Any],
) -> ReportFacts:
    presentation = REPORT_PRESENTATIONS[case.case_id]
    scope = dict(reportability["scope"])
    base: dict[str, Any] = {
        "contract_version": REPORT_CONTRACT_VERSION,
        "case_id": case.case_id,
        "question": presentation["question"],
        "scope": scope,
        "source_tables": list(case.source_tables),
        "metric_definition": case.metric_definition,
        "fact_ids": ["scope", "metric_definition", "result_rows"],
        "limitations": [
            "结论仅覆盖所列工厂与日期范围。",
            "图表数据来自已验证的固定统计结果，不重新计算业务指标。",
        ],
    }
    if reportability.get("status") != "reportable" or analysis is None:
        return ReportFacts.from_mapping(
            {
                **base,
                "status": "blocked",
                "rows": [],
                "blocked_reason": str(reportability.get("reason", "report_not_reportable")),
                "limitations": [
                    "当前问题未满足生成数值报告的事实条件。",
                    "系统未计算或展示未经确认的数值。",
                ],
            }
        )

    rows = analysis.get("rows")
    if not isinstance(rows, list):
        rows = []
    fact_ids = list(base["fact_ids"])
    if isinstance(analysis.get("trend"), Mapping):
        fact_ids.append("trend")
    return ReportFacts.from_mapping(
        {
            **base,
            "status": "reportable",
            "rows": rows,
            "fact_ids": fact_ids,
            "chart_requirement": presentation["chart"],
        }
    )


def report_title(case_id: str) -> str:
    return str(REPORT_PRESENTATIONS[case_id]["title"])


def build_dimension_report_facts(
    analysis: Mapping[str, Any], reportability: Mapping[str, Any]
) -> ReportFacts:
    scope = dict(reportability.get("scope", {}))
    points = analysis.get("high_variation_points")
    if reportability.get("status") != "reportable" or not isinstance(points, list) or not points:
        return ReportFacts.from_mapping(
            {
                "contract_version": REPORT_CONTRACT_VERSION,
                "status": "blocked",
                "case_id": "dimension_variation",
                "question": "尺寸测量点位波动分析",
                "scope": scope,
                "source_tables": [
                    "inspection_report",
                    "dimension_measurement",
                    "tolerance_catalog",
                ],
                "metric_definition": "按点位计算极差、标准差和极差占完整公差带比例。",
                "rows": [],
                "fact_ids": ["scope", "metric_definition", "result_rows", "sample_gate"],
                "limitations": ["当前分析结果不足以生成数值报告。"],
                "blocked_reason": str(reportability.get("reason", "dimension_analysis_incomplete")),
            }
        )

    rows = [
        {
            "measurement_point": point["measurement_point"],
            "sample_count": point["sample_count"],
            "lower_limit": point["lower_limit"],
            "upper_limit": point["upper_limit"],
            "range": point["range"],
            "std": point["std"],
            "range_ratio_percent": round(float(point["range_ratio"]) * 100, 2),
            "trend": point["trend"],
        }
        for point in points
    ]
    lacks_cpk_sample = any(not point.get("is_sample_reach_minimum_cpk", False) for point in points)
    limitations = ["结论仅覆盖所列工厂、日期和产品。"]
    if lacks_cpk_sample:
        limitations.append("样本数未达到125时，不计算或解释 Cpk/Ppk。")
    return ReportFacts.from_mapping(
        {
            "contract_version": REPORT_CONTRACT_VERSION,
            "status": "reportable",
            "case_id": "dimension_variation",
            "question": "2026年9月14日一厂后背门外饰板总成哪些测量点位波动较大",
            "scope": scope,
            "source_tables": [
                "inspection_report",
                "dimension_measurement",
                "tolerance_catalog",
            ],
            "metric_definition": "按点位计算极差、标准差和极差占完整公差带比例；样本数达到125时才计算 Cpk/Ppk。",
            "rows": rows,
            "fact_ids": ["scope", "metric_definition", "result_rows", "sample_gate"],
            "chart_requirement": {
                "chart_type": "bar",
                "dataset_id": "analysis_rows",
                "x_field": "measurement_point",
                "y_field": "range_ratio_percent",
                "title": "高波动点位极差占公差带比例",
            },
            "limitations": limitations,
        }
    )
