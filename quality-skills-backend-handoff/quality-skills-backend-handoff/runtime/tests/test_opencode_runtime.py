from __future__ import annotations

import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from fixtures.sample_data import dimension_variation_spec
from quality_analysis.opencode_runtime import (
    OpenCodeAuthenticationError,
    OpenCodeProtocolError,
    OpenCodeRuntimeError,
    OpenCodeSkillRuntime,
)


def fake_cli(directory: Path, stdout: str, delay_seconds: float = 0) -> Path:
    script = directory / "fake-opencode.py"
    source = (
        f"#!{sys.executable}\n"
        "import sys\n"
        "import time\n"
        "sys.stdin.read()\n"
        f"time.sleep({delay_seconds!r})\n"
        f"sys.stdout.write({stdout!r})\n"
    )
    script.write_text(source, encoding="utf-8")
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    return script


class OpenCodeSkillRuntimeTests(unittest.TestCase):
    def test_command_uses_fixed_title_and_optional_model(self):
        runtime = OpenCodeSkillRuntime(ROOT, binary="fixture-opencode")
        self.assertEqual(
            [
                "fixture-opencode",
                "run",
                "--format",
                "json",
                "--agent",
                "quality-python-planner",
                "--title",
                "quality-python-plan",
            ],
            runtime._command(),
        )
        modeled_runtime = OpenCodeSkillRuntime(
            ROOT, binary="fixture-opencode", model="fixture/model"
        )
        self.assertEqual(
            ["--model", "fixture/model"],
            modeled_runtime._command()[-2:],
        )

    def test_text_event_returns_valid_analysis_spec(self):
        event = json.dumps(
            {
                "type": "text",
                "sessionID": "ses_fixture",
                "part": {"text": json.dumps(dimension_variation_spec())},
            }
        ) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            binary = fake_cli(Path(directory), event)
            result = OpenCodeSkillRuntime(ROOT, binary=str(binary)).run("plan")
        self.assertEqual("ses_fixture", result.session_id)
        self.assertEqual("dimension_variation_v1", result.spec.recipe_id)

    def test_error_event_is_classified_as_authentication_failure(self):
        event = json.dumps(
            {
                "type": "error",
                "error": {"data": {"message": "Token refresh failed: 401"}},
            }
        ) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            binary = fake_cli(Path(directory), event)
            with self.assertRaises(OpenCodeAuthenticationError):
                OpenCodeSkillRuntime(ROOT, binary=str(binary)).run("plan")

    def test_markdown_or_non_json_text_is_rejected(self):
        event = json.dumps(
            {"type": "text", "part": {"text": "```json\n{}\n```"}}
        ) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            binary = fake_cli(Path(directory), event)
            with self.assertRaises(OpenCodeProtocolError):
                OpenCodeSkillRuntime(ROOT, binary=str(binary)).run("plan")

    def test_timeout_terminates_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = fake_cli(Path(directory), "", delay_seconds=1)
            runtime = OpenCodeSkillRuntime(
                ROOT, binary=str(binary), timeout_seconds=0.05
            )
            with self.assertRaises(OpenCodeRuntimeError):
                runtime.run("plan")


if __name__ == "__main__":
    unittest.main()
