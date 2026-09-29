"""Render validated ReportDraft objects into Markdown and ECharts configuration."""

from __future__ import annotations

import json
from decimal import Decimal
from typing import Any

from .contracts import ChartSpec, ReportContractError, ReportDraft, ReportFacts


def render_report(facts: ReportFacts, draft: ReportDraft | None = None) -> dict[str, Any]:
    if facts.status == "blocked":
        return {
            "status": "blocked",
            "markdown": _render_blocked(facts),
            "chart_option": None,
        }
    if draft is None:
        raise ReportContractError("reportable facts require a ReportDraft")
    chart = _render_chart(draft.chart, facts.rows)
    markdown = _render_markdown(facts, draft, chart)
    return {"status": "success", "markdown": markdown, "chart_option": chart}


def _render_chart(chart: ChartSpec, rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        raise ReportContractError("chart requires non-empty rows")
    for row in rows:
        if chart.x_field not in row or chart.y_field not in row:
            raise ReportContractError("chart references a field absent from result rows")
        if not isinstance(row[chart.y_field], (int, float, Decimal)):
            raise ReportContractError("chart y_field must be numeric")

    if chart.chart_type == "pie":
        return {
            "title": {"text": chart.title, "left": "center"},
            "tooltip": {"trigger": "item"},
            "series": [
                {
                    "name": chart.title,
                    "type": "pie",
                    "radius": "60%",
                    "data": [
                        {"name": str(row[chart.x_field]), "value": _number(row[chart.y_field])}
                        for row in rows
                    ],
                }
            ],
        }

    return {
        "title": {"text": chart.title, "left": "center"},
        "tooltip": {"trigger": "axis"},
        "xAxis": {
            "type": "category",
            "data": [str(row[chart.x_field]) for row in rows],
            "axisLabel": {"interval": 0},
        },
        "yAxis": {"type": "value"},
        "series": [
            {
                "name": chart.title,
                "type": chart.chart_type,
                "data": [_number(row[chart.y_field]) for row in rows],
                "smooth": chart.chart_type == "line",
            }
        ],
    }


def _render_markdown(facts: ReportFacts, draft: ReportDraft, chart: dict[str, Any]) -> str:
    summary = "\n".join(
        f"- {item.text}（依据：{', '.join(item.fact_ids)}）" for item in draft.executive_summary
    )
    scope = "；".join(f"{key}: {_display(value)}" for key, value in facts.scope.items())
    sources = "、".join(facts.source_tables)
    limitations = list(dict.fromkeys([*facts.limitations, *draft.limitations]))
    chart_json = json.dumps(chart, ensure_ascii=False, separators=(",", ":"))
    return "\n".join(
        [
            f"# {draft.title}",
            "",
            "## 执行摘要",
            summary,
            "",
            "## 核心数据",
            _markdown_table(facts.rows),
            "",
            "## 图表",
            "```echarts",
            chart_json,
            "```",
            "",
            "## 统计口径与范围",
            f"- 范围：{scope}",
            f"- 口径：{facts.metric_definition}",
            f"- 数据表：{sources}",
            "",
            "## 限制条件",
            *[f"- {item}" for item in limitations],
        ]
    )


def _render_blocked(facts: ReportFacts) -> str:
    scope = "；".join(f"{key}: {_display(value)}" for key, value in facts.scope.items())
    return "\n".join(
        [
            f"# {facts.question}",
            "",
            "## 当前无法生成数值报告",
            f"- 原因：{facts.blocked_reason}",
            f"- 范围：{scope}",
            f"- 数据表：{'、'.join(facts.source_tables)}",
            "- 系统未平均或补造未经确认的指标，因此未生成数值图表。",
        ]
    )


def _markdown_table(rows: list[dict[str, Any]]) -> str:
    columns = list(rows[0])
    header = "| " + " | ".join(columns) + " |"
    divider = "| " + " | ".join("---" for _ in columns) + " |"
    body = [
        "| " + " | ".join(_display(row.get(column)) for column in columns) + " |"
        for row in rows
    ]
    return "\n".join([header, divider, *body])


def _number(value: int | float | Decimal) -> int | float:
    if isinstance(value, Decimal):
        return float(value)
    return value


def _display(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}".rstrip("0").rstrip(".")
    return str(value).replace("|", "\\|").replace("\n", " ")
