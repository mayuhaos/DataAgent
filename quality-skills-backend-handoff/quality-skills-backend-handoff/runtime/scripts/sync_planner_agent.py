"""Build the no-tool OpenCode planner prompt from the quality-python Skill."""

from __future__ import annotations

import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILL_DIR = ROOT / ".opencode" / "skills" / "quality-python"
AGENT_PATH = ROOT / ".opencode" / "agents" / "quality-python-planner.md"


HEADER = """---
description: Selects a registered quality-analysis recipe and returns a typed AnalysisSpec in one model turn.
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

# Quality Python Planner

You are the fast planning boundary of the quality-python Skill. The Skill contract and recipe catalog are preloaded below. Do not load a Skill, read files, search, call tools, write Python, write SQL, execute code, or write a report. Those actions create extra model turns and are outside this boundary.

The request gives the canonical query, available row fields, and any approved rule values. Select one registered recipe only when its required semantics are present. Preserve supplied field names and rule values exactly. Never invent a quality threshold, source field, join, or metric formula.

Return exactly one valid JSON object. Do not wrap it in Markdown and do not add explanatory text. For `recipe` mode, omit `reason`. For `legacy` mode, set `recipe_id` and `recipe_version` to null and `parameters` to an empty object.

The upstream DataAgent owns table selection, joins, SQL, and data retrieval. The fixed capability library owns execution and numerical calculation. You only select the recipe and parameters.

## Preloaded Analysis Contract

"""


def render() -> str:
    contract = (SKILL_DIR / "references" / "analysis_contract.md").read_text(
        encoding="utf-8"
    ).strip()
    catalog = (SKILL_DIR / "references" / "recipe_catalog.md").read_text(
        encoding="utf-8"
    ).strip()
    return f"{HEADER}{contract}\n\n## Preloaded Recipe Catalog\n\n{catalog}\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when the committed Agent prompt is not generated from the Skill references",
    )
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not AGENT_PATH.exists() or AGENT_PATH.read_text(encoding="utf-8") != expected:
            raise SystemExit("quality-python planner prompt is out of sync; run sync_planner_agent.py")
        return 0
    AGENT_PATH.write_text(expected, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
