"""Run the five spray POCs through verified facts and the quality-report Skill."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_analysis import (
    SPRAY_CASES,
    OpenCodeSkillRuntime,
    assess_spray_report_facts,
    execute_analysis,
    get_spray_case,
    validate_spray_case_spec,
)
from quality_analysis.operators import json_safe
from quality_analysis.spray_cases import request_path, source_metric_values
from quality_db import QualityDbReader
from quality_report import build_spray_report_facts, render_report
from quality_report.opencode_runtime import OpenCodeReportRuntime


CASE_ORDER = (
    "weekly_first_pass_rate",
    "defect_top3_share",
    "three_defect_categories",
    "shift_defect_rate",
    "source_overall_pass_rate",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", choices=CASE_ORDER, action="append")
    parser.add_argument("--factory-id", type=int, default=1)
    parser.add_argument("--start-date", default="2026-06-01")
    parser.add_argument("--end-date", default="2026-07-01")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--model")
    parser.add_argument("--python-agent-timeout-seconds", type=int, default=70)
    parser.add_argument("--report-agent-timeout-seconds", type=int, default=70)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    selected_case_ids = args.case or CASE_ORDER
    preflight_started = time.monotonic()
    reader = QualityDbReader.from_environment()
    connection = reader.check_connection("live_report_cases_preflight")
    shared_preflight_ms = round((time.monotonic() - preflight_started) * 1000)
    python_runtime = OpenCodeSkillRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.python_agent_timeout_seconds,
    )
    report_runtime = OpenCodeReportRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.report_agent_timeout_seconds,
    )
    results: list[dict[str, object]] = []

    for case_id in selected_case_ids:
        case_started = time.monotonic()
        case = get_spray_case(case_id)
        python_result = python_runtime.run(
            request_path(ROOT, case).read_text(encoding="utf-8")
        )
        validate_spray_case_spec(case, python_result.spec)
        query_started = time.monotonic()
        rows = reader.execute_template(
            case.template_id,
            {
                "factory_id": args.factory_id,
                "start_date": args.start_date,
                "end_date": args.end_date,
                "limit": args.limit,
            },
            f"live_report_case_{case.case_id}",
        )
        database_read_ms = round((time.monotonic() - query_started) * 1000)
        source_values = source_metric_values(case, rows)
        conflict = source_values if case.source_metric_column and len(source_values) != 1 else None
        analysis_started = time.monotonic()
        analysis = None if conflict is not None else execute_analysis(python_result.spec, rows)
        python_execution_ms = round((time.monotonic() - analysis_started) * 1000)
        reportability = assess_spray_report_facts(
            case,
            analysis,
            factory_id=args.factory_id,
            start_date=args.start_date,
            end_date=args.end_date,
            source_metric_conflict=conflict,
        )
        facts = build_spray_report_facts(case, analysis, reportability)
        render_started = time.monotonic()
        if facts.status == "blocked":
            report_result = None
            rendered = render_report(facts)
            report_agent_ms = 0
        else:
            report_result = report_runtime.run(facts)
            report_agent_ms = report_result.elapsed_ms
            rendered = render_report(facts, report_result.draft)
        render_ms = round((time.monotonic() - render_started) * 1000) - report_agent_ms
        results.append(
            {
                "case_id": case_id,
                "python_skill": {
                    "session_id": python_result.session_id,
                    "analysis_spec": python_result.spec.to_dict(),
                    "elapsed_ms": python_result.elapsed_ms,
                },
                "input_row_count": len(rows),
                "report_facts": facts.to_dict(),
                "report_draft": report_result.draft.to_dict() if report_result else None,
                "rendered_report": rendered,
                "timing": {
                    "python_skill_ms": python_result.elapsed_ms,
                    "database_read_ms": database_read_ms,
                    "python_execution_ms": python_execution_ms,
                    "report_skill_ms": report_agent_ms,
                    "render_ms": max(0, render_ms),
                    "case_elapsed_ms": round((time.monotonic() - case_started) * 1000),
                },
            }
        )

    print(
        json.dumps(
            json_safe(
                {
                    "connection": {
                        "server_version": connection.server_version,
                        "account_is_read_only": connection.account_is_read_only,
                    },
                    "shared_preflight_ms": shared_preflight_ms,
                    "results": results,
                }
            ),
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
