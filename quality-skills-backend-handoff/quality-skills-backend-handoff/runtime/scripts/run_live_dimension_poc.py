"""Run one bounded dimension-variation POC against QUALITY_DB."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_analysis import execute_analysis
from quality_db import QualityDbReader


REQUIRED_TABLES = {
    "dimension_measurement",
    "inspection_report",
    "tolerance_catalog",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--factory-id", type=int, required=True)
    parser.add_argument("--measurement-date", required=True)
    parser.add_argument("--part-name", required=True)
    parser.add_argument("--rules-file", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=500)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rules = json.loads(args.rules_file.read_text(encoding="utf-8"))
    reader = QualityDbReader.from_environment()
    connection = reader.check_connection("live_dimension_poc_preflight")
    available_tables = set(reader.list_tables("live_dimension_poc_metadata"))
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
        "live_dimension_poc_query",
    )
    spec = {
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
            "rules": rules,
        },
        "reason": "Bounded live dimension variation POC.",
    }
    analysis = execute_analysis(spec, rows)
    print(
        json.dumps(
            {
                "connection": {
                    "server_version": connection.server_version,
                    "account_is_read_only": connection.account_is_read_only,
                },
                "input_row_count": len(rows),
                "analysis": analysis,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
