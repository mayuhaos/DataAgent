"""Run the smallest authorized QUALITY_DB connection preflight."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_db import QualityDbReader


def main() -> int:
    reader = QualityDbReader.from_environment()
    info = reader.check_connection()
    tables = reader.list_tables()
    print(
        json.dumps(
            {
                "status": "connected",
                "server_version": info.server_version,
                "account_is_read_only": info.account_is_read_only,
                "table_count": len(tables),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
