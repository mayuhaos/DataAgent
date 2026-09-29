"""Local OpenCode adapter for the quality-report Skill."""

from __future__ import annotations

import json
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

from quality_analysis.opencode_runtime import (
    OpenCodeAuthenticationError,
    OpenCodeProtocolError,
    OpenCodeRuntimeError,
    OpenCodeSkillRuntime,
)

from .contracts import ReportContractError, ReportDraft, ReportFacts


@dataclass(frozen=True)
class OpenCodeReportRunResult:
    draft: ReportDraft
    session_id: str | None
    elapsed_ms: int


class OpenCodeReportRuntime:
    def __init__(
        self,
        working_directory: Path,
        binary: str = "opencode",
        agent: str = "quality-report-writer",
        model: str | None = None,
        timeout_seconds: int = 70,
    ) -> None:
        self.working_directory = Path(working_directory)
        self.binary = binary
        self.agent = agent
        self.model = model or os.environ.get("QUALITY_OPENCODE_MODEL")
        self.timeout_seconds = timeout_seconds

    def run(self, facts: ReportFacts) -> OpenCodeReportRunResult:
        if facts.status != "reportable":
            raise ReportContractError("blocked ReportFacts must bypass the Report Agent")
        prompt = "Return only one valid ReportDraft JSON for these ReportFacts:\n" + json.dumps(
            facts.to_dict(), ensure_ascii=False
        )
        command = [
            self.binary,
            "run",
            "--format",
            "json",
            "--agent",
            self.agent,
            "--title",
            "quality-report",
        ]
        if self.model:
            command.extend(["--model", self.model])

        started = time.monotonic()
        try:
            completed = subprocess.run(
                command,
                input=prompt,
                text=True,
                capture_output=True,
                cwd=self.working_directory,
                env=dict(os.environ),
                timeout=self.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise OpenCodeRuntimeError("OpenCode report writer timed out") from exc
        except OSError as exc:
            raise OpenCodeRuntimeError("OpenCode CLI could not start") from exc

        elapsed_ms = round((time.monotonic() - started) * 1000)
        fragments, session_id, provider_error = OpenCodeSkillRuntime._parse_events(completed.stdout)
        if provider_error:
            raise self._classify_error(provider_error)
        if completed.returncode != 0:
            raise self._classify_error(OpenCodeSkillRuntime._safe_error(completed.stderr))
        raw = "".join(fragments).strip()
        if not raw:
            raise OpenCodeProtocolError("OpenCode returned no report draft")
        try:
            draft = ReportDraft.from_mapping(json.loads(raw), facts)
        except (json.JSONDecodeError, ReportContractError) as exc:
            raise OpenCodeProtocolError("OpenCode returned an invalid ReportDraft") from exc
        return OpenCodeReportRunResult(draft=draft, session_id=session_id, elapsed_ms=elapsed_ms)

    @staticmethod
    def _classify_error(message: str) -> OpenCodeRuntimeError:
        normalized = message.lower()
        if any(marker in normalized for marker in ("401", "authentication", "token refresh", "api key", "credential")):
            return OpenCodeAuthenticationError("OpenCode provider authentication is unavailable")
        return OpenCodeRuntimeError("OpenCode CLI request failed")
