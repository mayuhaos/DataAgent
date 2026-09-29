"""Plan and execute one bounded live dimension-variation POC."""

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
from quality_db import QualityDbReader


REQUIRED_TABLES = {
    "dimension_measurement",
    "inspection_report",
    "tolerance_catalog",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request-file", type=Path, required=True)
    parser.add_argument("--factory-id", type=int, required=True)
    parser.add_argument("--measurement-date", required=True)
    parser.add_argument("--part-name", required=True)
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--model")
    parser.add_argument("--agent-timeout-seconds", type=int, default=70)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    case_started = time.monotonic()
    prompt = args.request_file.read_text(encoding="utf-8")
    planner = OpenCodeSkillRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.agent_timeout_seconds,
    )
    agent_result = planner.run(prompt)
    spec = agent_result.spec
    if spec.mode != "recipe" or spec.recipe_id != "dimension_variation_v1":
        raise RuntimeError("the supplied live POC requires dimension_variation_v1")

    database_read_started = time.monotonic()
    reader = QualityDbReader.from_environment()
    connection = reader.check_connection("live_agent_dimension_poc_preflight")
    available_tables = set(reader.list_tables("live_agent_dimension_poc_metadata"))
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
        "live_agent_dimension_poc_query",
    )
    database_read_ms = round((time.monotonic() - database_read_started) * 1000)
    python_started = time.monotonic()
    analysis = execute_analysis(spec, rows)
    python_execution_ms = round((time.monotonic() - python_started) * 1000)
    report_facts = assess_dimension_report_facts(
        analysis,
        factory_id=args.factory_id,
        measurement_date=args.measurement_date,
        part_name=args.part_name,
    )
    print(
        json.dumps(
            {
                "agent": {
                    "session_id": agent_result.session_id,
                    "elapsed_ms": agent_result.elapsed_ms,
                    "analysis_spec": spec.to_dict(),
                },
                "connection": {
                    "server_version": connection.server_version,
                    "account_is_read_only": connection.account_is_read_only,
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
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
