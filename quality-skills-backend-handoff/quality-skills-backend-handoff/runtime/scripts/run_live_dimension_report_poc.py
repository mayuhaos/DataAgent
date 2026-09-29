"""Run the complex dimension POC through Python and Report Skills."""

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
    OpenCodeSkillRuntime,
    assess_dimension_report_facts,
    execute_analysis,
)
from quality_analysis.operators import json_safe
from quality_db import QualityDbReader
from quality_report import build_dimension_report_facts, render_report
from quality_report.opencode_runtime import OpenCodeReportRuntime


REQUIRED_TABLES = {"dimension_measurement", "inspection_report", "tolerance_catalog"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request-file", type=Path, required=True)
    parser.add_argument("--factory-id", type=int, required=True)
    parser.add_argument("--measurement-date", required=True)
    parser.add_argument("--part-name", required=True)
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--model")
    parser.add_argument("--python-agent-timeout-seconds", type=int, default=70)
    parser.add_argument("--report-agent-timeout-seconds", type=int, default=70)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    case_started = time.monotonic()
    python_runtime = OpenCodeSkillRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.python_agent_timeout_seconds,
    )
    python_result = python_runtime.run(args.request_file.read_text(encoding="utf-8"))
    if python_result.spec.recipe_id != "dimension_variation_v1":
        raise RuntimeError("complex dimension POC requires dimension_variation_v1")

    database_started = time.monotonic()
    reader = QualityDbReader.from_environment()
    connection = reader.check_connection("live_dimension_report_preflight")
    available_tables = set(reader.list_tables("live_dimension_report_metadata"))
    missing_tables = sorted(REQUIRED_TABLES - available_tables)
    if missing_tables:
        raise RuntimeError("required tables are missing: " + ", ".join(missing_tables))
    rows = reader.execute_template(
        "dimension_variation_source_v1",
        {
            "factory_id": args.factory_id,
            "measurement_date": args.measurement_date,
            "part_name": args.part_name,
            "limit": args.limit,
        },
        "live_dimension_report_query",
    )
    database_read_ms = round((time.monotonic() - database_started) * 1000)
    analysis_started = time.monotonic()
    analysis = execute_analysis(python_result.spec, rows)
    python_execution_ms = round((time.monotonic() - analysis_started) * 1000)
    reportability = assess_dimension_report_facts(
        analysis,
        factory_id=args.factory_id,
        measurement_date=args.measurement_date,
        part_name=args.part_name,
    )
    facts = build_dimension_report_facts(analysis, reportability)
    report_runtime = OpenCodeReportRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.report_agent_timeout_seconds,
    )
    render_started = time.monotonic()
    report_result = report_runtime.run(facts)
    rendered = render_report(facts, report_result.draft)
    render_ms = max(0, round((time.monotonic() - render_started) * 1000) - report_result.elapsed_ms)
    print(
        json.dumps(
            json_safe(
                {
                    "connection": {
                        "server_version": connection.server_version,
                        "account_is_read_only": connection.account_is_read_only,
                    },
                    "input_row_count": len(rows),
                    "report_facts": facts.to_dict(),
                    "report_draft": report_result.draft.to_dict(),
                    "rendered_report": rendered,
                    "timing": {
                        "python_skill_ms": python_result.elapsed_ms,
                        "database_read_ms": database_read_ms,
                        "python_execution_ms": python_execution_ms,
                        "report_skill_ms": report_result.elapsed_ms,
                        "render_ms": render_ms,
                        "case_elapsed_ms": round((time.monotonic() - case_started) * 1000),
                    },
                }
            ),
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
