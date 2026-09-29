"""Run the local OpenCode quality-python planner without a browser."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_analysis import OpenCodeSkillRuntime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request-file", type=Path, required=True)
    parser.add_argument("--model")
    parser.add_argument("--timeout-seconds", type=int, default=90)
    args = parser.parse_args()

    runtime = OpenCodeSkillRuntime(
        working_directory=ROOT,
        model=args.model,
        timeout_seconds=args.timeout_seconds,
    )
    result = runtime.run(args.request_file.read_text(encoding="utf-8"))
    print(
        json.dumps(
            {
                "session_id": result.session_id,
                "elapsed_ms": result.elapsed_ms,
                "analysis_spec": result.spec.to_dict(),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
