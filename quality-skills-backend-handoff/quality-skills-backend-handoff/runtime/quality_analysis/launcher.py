"""Fixed launcher compatible with the future DataAgent stdin/stdout contract."""

from __future__ import annotations

import json
import sys
from typing import Any, Mapping, Sequence

from .contracts import AnalysisSpec
from .operators import json_safe
from .recipes import execute_analysis


def run_spec(
    spec_data: Mapping[str, Any], rows: Sequence[Mapping[str, Any]]
) -> dict[str, Any]:
    spec = AnalysisSpec.from_mapping(spec_data)
    return execute_analysis(spec, rows)


def render_launcher(spec_data: Mapping[str, Any]) -> str:
    """Render the stable script shape that a future PythonGenerateNode can emit."""

    spec = AnalysisSpec.from_mapping(spec_data)
    embedded_spec = json.dumps(spec.to_dict(), ensure_ascii=False, sort_keys=True)
    return f"""import json
import sys

from quality_analysis.launcher import run_spec

ANALYSIS_SPEC = json.loads({embedded_spec!r})

input_rows = json.load(sys.stdin)
result = run_spec(ANALYSIS_SPEC, input_rows)
print(json.dumps(result, ensure_ascii=False, default=str))
"""


def main() -> int:
    request = json.load(sys.stdin)
    rows = request.pop("rows")
    result = run_spec(request, rows)
    print(json.dumps(json_safe(result), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
