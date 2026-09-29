"""Check the local files OpenCode needs to discover the POC Skill."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / ".opencode" / "skills" / "quality-python" / "SKILL.md"
AGENT = ROOT / ".opencode" / "agents" / "quality-python-planner.md"
REFERENCES = ROOT / ".opencode" / "skills" / "quality-python" / "references"


def main() -> int:
    required = [
        SKILL,
        AGENT,
        REFERENCES / "analysis_contract.md",
        REFERENCES / "recipe_catalog.md",
        REFERENCES / "quality_table_catalog.md",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("Missing OpenCode Skill files: " + ", ".join(missing))

    text = SKILL.read_text(encoding="utf-8")
    if not text.startswith("---\n") or "name: quality-python" not in text:
        raise SystemExit("Skill frontmatter is missing name: quality-python")
    if "description:" not in text:
        raise SystemExit("Skill frontmatter is missing description")

    print("quality-python Skill layout verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
