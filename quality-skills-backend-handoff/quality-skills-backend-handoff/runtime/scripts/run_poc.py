"""Run deterministic fixture scenarios without starting OpenCode or a model."""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from fixtures.sample_data import (
    defect_top_spec,
    dimension_variation_rows,
    dimension_variation_spec,
    paint_quality_rows,
    weekly_yield_rows,
    weekly_yield_spec,
)
from quality_analysis import execute_analysis


def main() -> int:
    results = {
        "dimension_variation": execute_analysis(
            dimension_variation_spec(), dimension_variation_rows()
        ),
        "weekly_yield": execute_analysis(weekly_yield_spec(), weekly_yield_rows()),
        "defect_top_3": execute_analysis(defect_top_spec(), paint_quality_rows()),
    }
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
