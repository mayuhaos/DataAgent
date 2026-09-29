from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PlannerAgentTests(unittest.TestCase):
    def test_agent_prompt_is_synced_and_disables_runtime_tools(self):
        completed = subprocess.run(
            [sys.executable, "scripts/sync_planner_agent.py", "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)

        agent = (ROOT / ".opencode" / "agents" / "quality-python-planner.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("read: deny", agent)
        self.assertIn("skill: deny", agent)
        self.assertIn("dimension_variation_v1", agent)
        self.assertIn("tabular_aggregate_v1", agent)
        self.assertIn("Return exactly one valid JSON object", agent)


if __name__ == "__main__":
    unittest.main()
