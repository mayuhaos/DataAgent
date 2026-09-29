from __future__ import annotations

import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_analysis import assess_dimension_report_facts, assess_spray_report_facts, get_spray_case
from quality_report import (
    ReportContractError,
    ReportDraft,
    build_dimension_report_facts,
    build_spray_report_facts,
    render_report,
)
from quality_report.opencode_runtime import OpenCodeReportRuntime


def reportable_facts():
    case = get_spray_case("weekly_first_pass_rate")
    analysis = {
        "status": "success",
        "rows": [
            {"week": "2026-W23", "check_total": 100, "first_pass_count": 95, "first_pass_rate": 95.0},
            {"week": "2026-W24", "check_total": 100, "first_pass_count": 97, "first_pass_rate": 97.0},
        ],
        "trend": {"direction": "rising"},
    }
    reportability = assess_spray_report_facts(
        case,
        analysis,
        factory_id=1,
        start_date="2026-06-01",
        end_date="2026-07-01",
    )
    return build_spray_report_facts(case, analysis, reportability)


def valid_draft(facts):
    return {
        "contract_version": "1.0",
        "status": "report",
        "title": "一次合格率周趋势",
        "executive_summary": [
            {
                "text": "统计周期内一次合格率整体呈上升趋势。",
                "fact_ids": ["scope", "metric_definition", "result_rows", "trend"],
            }
        ],
        "chart": {
            "chart_type": "line",
            "dataset_id": "analysis_rows",
            "x_field": "week",
            "y_field": "first_pass_rate",
            "title": "一次合格率周趋势",
        },
        "limitations": ["需结合后续周期持续观察。"],
    }


def fake_cli(directory: Path, stdout: str) -> Path:
    script = directory / "fake-opencode-report.py"
    script.write_text(
        f"#!{sys.executable}\nimport sys\nsys.stdin.read()\nsys.stdout.write({stdout!r})\n",
        encoding="utf-8",
    )
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    return script


class ReportContractTests(unittest.TestCase):
    def test_dimension_facts_render_a_fixed_range_ratio_bar_chart(self):
        analysis = {
            "analysis_summary": {"total_points_analyzed": 42, "high_variation_points_count": 1},
            "high_variation_points": [
                {
                    "measurement_point": "P1",
                    "sample_count": 10,
                    "lower_limit": -0.75,
                    "upper_limit": 0.75,
                    "range": 0.83,
                    "std": 0.29,
                    "range_ratio": 0.5533,
                    "trend": "falling_toward_lower",
                    "is_sample_reach_minimum_cpk": False,
                }
            ],
        }
        reportability = assess_dimension_report_facts(
            analysis,
            factory_id=1,
            measurement_date="2026-09-14",
            part_name="demo",
        )
        facts = build_dimension_report_facts(analysis, reportability)
        self.assertEqual("range_ratio_percent", facts.chart_requirement.y_field)
        self.assertIn("Cpk/Ppk", facts.limitations[1])
        draft = ReportDraft.from_mapping(
            {
                "contract_version": "1.0",
                "status": "report",
                "title": "高波动点位分析",
                "executive_summary": [
                    {"text": "高波动点位已按既定规则识别。", "fact_ids": ["scope", "result_rows", "sample_gate"]}
                ],
                "chart": {
                    "chart_type": "bar",
                    "dataset_id": "analysis_rows",
                    "x_field": "measurement_point",
                    "y_field": "range_ratio_percent",
                    "title": "高波动点位极差占公差带比例",
                },
                "limitations": [],
            },
            facts,
        )
        rendered = render_report(facts, draft)
        self.assertEqual([55.33], rendered["chart_option"]["series"][0]["data"])

    def test_valid_draft_renders_a_fixed_line_chart(self):
        facts = reportable_facts()
        draft = ReportDraft.from_mapping(valid_draft(facts), facts)
        rendered = render_report(facts, draft)
        self.assertEqual("success", rendered["status"])
        self.assertIn("```echarts", rendered["markdown"])
        option = rendered["chart_option"]
        self.assertEqual("line", option["series"][0]["type"])
        self.assertEqual([95.0, 97.0], option["series"][0]["data"])
        self.assertEqual(1, rendered["markdown"].count("结论仅覆盖所列工厂与日期范围。"))
        json.dumps(option)

    def test_draft_rejects_unknown_fact_reference(self):
        facts = reportable_facts()
        draft = valid_draft(facts)
        draft["executive_summary"][0]["fact_ids"] = ["not_a_fact"]
        with self.assertRaises(ReportContractError):
            ReportDraft.from_mapping(draft, facts)

    def test_draft_rejects_unapproved_chart_field(self):
        facts = reportable_facts()
        draft = valid_draft(facts)
        draft["chart"]["y_field"] = "check_total"
        with self.assertRaises(ReportContractError):
            ReportDraft.from_mapping(draft, facts)

    def test_blocked_facts_render_without_a_chart(self):
        case = get_spray_case("source_overall_pass_rate")
        reportability = {
            "status": "blocked",
            "reason": "source_metric_conflict",
            "scope": {"factory_id": 1, "start_date": "2026-06-01", "end_date_exclusive": "2026-07-01"},
        }
        facts = build_spray_report_facts(case, None, reportability)
        rendered = render_report(facts)
        self.assertEqual("blocked", rendered["status"])
        self.assertNotIn("```echarts", rendered["markdown"])


class ReportRuntimeTests(unittest.TestCase):
    def test_report_runtime_parses_a_typed_draft(self):
        facts = reportable_facts()
        event = json.dumps(
            {
                "type": "text",
                "sessionID": "ses_report_fixture",
                "part": {"text": json.dumps(valid_draft(facts), ensure_ascii=False)},
            }
        ) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            binary = fake_cli(Path(directory), event)
            result = OpenCodeReportRuntime(ROOT, binary=str(binary)).run(facts)
        self.assertEqual("ses_report_fixture", result.session_id)
        self.assertEqual("line", result.draft.chart.chart_type)

    def test_report_agent_prompt_is_synced(self):
        import subprocess

        completed = subprocess.run(
            [sys.executable, "scripts/sync_report_agent.py", "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)


if __name__ == "__main__":
    unittest.main()
