"""Run the five locked spray cases through Agent, read-only query, and fixed Python."""

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
from quality_analysis.spray_cases import request_path, source_metric_values
from quality_analysis.operators import json_safe
from quality_db import QualityDbReader


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", choices=sorted(SPRAY_CASES), action="append")
    parser.add_argument("--factory-id", type=int, default=1)
    parser.add_argument("--start-date", default="2026-06-01")
    parser.add_argument("--end-date", default="2026-07-01")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--model")
    parser.add_argument("--agent-timeout-seconds", type=int, default=70)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    selected_case_ids = args.case or sorted(SPRAY_CASES)
    preflight_started = time.monotonic()
    reader = QualityDbReader.from_environment()
    connection = reader.check_connection("live_spray_cases_preflight")
    shared_preflight_ms = round((time.monotonic() - preflight_started) * 1000)
    planner = OpenCodeSkillRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.agent_timeout_seconds,
    )
    results: list[dict[str, object]] = []

    for case_id in selected_case_ids:
        case_started = time.monotonic()
        case = get_spray_case(case_id)
        prompt = request_path(ROOT, case).read_text(encoding="utf-8")
        agent_result = planner.run(prompt)
        validate_spray_case_spec(case, agent_result.spec)
        query_started = time.monotonic()
        rows = reader.execute_template(
            case.template_id,
            {
                "factory_id": args.factory_id,
                "start_date": args.start_date,
                "end_date": args.end_date,
                "limit": args.limit,
            },
            f"live_spray_case_{case.case_id}",
        )
        database_read_ms = round((time.monotonic() - query_started) * 1000)

        metric_values = source_metric_values(case, rows)
        conflict = metric_values if case.source_metric_column and len(metric_values) != 1 else None
        python_started = time.monotonic()
        analysis = None if conflict is not None else execute_analysis(agent_result.spec, rows)
        python_execution_ms = round((time.monotonic() - python_started) * 1000)
        report_facts = assess_spray_report_facts(
            case,
            analysis,
            factory_id=args.factory_id,
            start_date=args.start_date,
            end_date=args.end_date,
            source_metric_conflict=conflict,
        )
        results.append(
            {
                "case_id": case.case_id,
                "agent": {
                    "session_id": agent_result.session_id,
                    "elapsed_ms": agent_result.elapsed_ms,
                    "analysis_spec": agent_result.spec.to_dict(),
                },
                "input_row_count": len(rows),
                "timing": {
                    "agent_elapsed_ms": agent_result.elapsed_ms,
                    "database_read_ms": database_read_ms,
                    "python_execution_ms": python_execution_ms,
                    "case_elapsed_ms": round((time.monotonic() - case_started) * 1000),
                },
                "analysis": analysis,
                "report_facts": report_facts,
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
