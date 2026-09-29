"""Build the no-tool OpenCode report writer prompt from the quality-report Skill."""

from __future__ import annotations

import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILL_DIR = ROOT / ".opencode" / "skills" / "quality-report"
AGENT_PATH = ROOT / ".opencode" / "agents" / "quality-report-writer.md"


HEADER = """---
description: Writes an evidence-linked ReportDraft and selects only the pre-approved chart intent in one model turn.
mode: primary
permission:
  read: deny
  edit: deny
  bash: deny
  grep: deny
  glob: deny
  list: deny
  skill: deny
  external_directory: deny
---

# Quality Report Writer

You receive one validated ReportFacts object. Return one ReportDraft JSON object only. Do not load a Skill, read files, use tools, query data, calculate metrics, write Markdown, write ECharts Option JSON, write code, or create a report outside the JSON contract.

The facts are authoritative. Every executive-summary observation must cite fact IDs supplied in ReportFacts. Preserve scope and limitations. Copy the chart requirement's chart type, dataset ID, x field, and y field exactly; only the chart title may be phrased naturally.

## Preloaded Report Contract

"""


def render() -> str:
    contract = (SKILL_DIR / "references" / "report_contract.md").read_text(
        encoding="utf-8"
    ).strip()
    return f"{HEADER}{contract}\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not AGENT_PATH.exists() or AGENT_PATH.read_text(encoding="utf-8") != expected:
            raise SystemExit("quality-report writer prompt is out of sync; run sync_report_agent.py")
        return 0
    AGENT_PATH.write_text(expected, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
