"""Local OpenCode CLI adapter for the quality-python Skill POC."""

from __future__ import annotations

import json
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from .contracts import AnalysisContractError, AnalysisSpec


class OpenCodeRuntimeError(RuntimeError):
    """Base error for local OpenCode execution failures."""


class OpenCodeAuthenticationError(OpenCodeRuntimeError):
    """The local OpenCode profile has no valid provider credential."""


class OpenCodeProtocolError(OpenCodeRuntimeError):
    """OpenCode returned no valid typed Skill result."""


@dataclass(frozen=True)
class OpenCodeRunResult:
    spec: AnalysisSpec
    session_id: str | None
    elapsed_ms: int


class OpenCodeSkillRuntime:
    """Runs one named OpenCode Agent without a shell or browser dependency."""

    def __init__(
        self,
        working_directory: Path,
        binary: str = "opencode",
        agent: str = "quality-python-planner",
        model: str | None = None,
        timeout_seconds: int = 90,
    ) -> None:
        self.working_directory = Path(working_directory)
        self.binary = binary
        self.agent = agent
        self.model = model or os.environ.get("QUALITY_OPENCODE_MODEL")
        self.timeout_seconds = timeout_seconds

    def run(self, prompt: str) -> OpenCodeRunResult:
        if not prompt.strip():
            raise OpenCodeProtocolError("OpenCode prompt is empty")
        command = self._command()

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
            raise OpenCodeRuntimeError("OpenCode agent timed out") from exc
        except OSError as exc:
            raise OpenCodeRuntimeError("OpenCode CLI could not start") from exc

        elapsed_ms = round((time.monotonic() - started) * 1000)
        events, session_id, provider_error = self._parse_events(completed.stdout)
        stderr = self._safe_error(completed.stderr)
        if provider_error:
            raise self._classify_error(provider_error)
        if completed.returncode != 0:
            raise self._classify_error(stderr or "OpenCode CLI exited unsuccessfully")

        text_output = "".join(events).strip()
        if not text_output:
            raise OpenCodeProtocolError("OpenCode returned no text result")
        try:
            value = json.loads(text_output)
            spec = AnalysisSpec.from_mapping(value)
        except (json.JSONDecodeError, AnalysisContractError) as exc:
            raise OpenCodeProtocolError("OpenCode returned an invalid AnalysisSpec") from exc
        return OpenCodeRunResult(spec=spec, session_id=session_id, elapsed_ms=elapsed_ms)

    def _command(self) -> list[str]:
        """Avoid OpenCode's otherwise unnecessary title-generation model turn."""

        command = [
            self.binary,
            "run",
            "--format",
            "json",
            "--agent",
            self.agent,
            "--title",
            "quality-python-plan",
        ]
        if self.model:
            command.extend(["--model", self.model])
        return command

    @staticmethod
    def _parse_events(stdout: str) -> tuple[list[str], str | None, str | None]:
        fragments: list[str] = []
        session_id: str | None = None
        provider_error: str | None = None
        for line in stdout.splitlines():
            if not line.strip():
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError as exc:
                raise OpenCodeProtocolError("OpenCode emitted non-JSON output") from exc
            if not isinstance(event, Mapping):
                raise OpenCodeProtocolError("OpenCode emitted an invalid event")
            candidate_session = event.get("sessionID")
            if isinstance(candidate_session, str):
                session_id = candidate_session
            if event.get("type") == "text":
                part = event.get("part")
                if isinstance(part, Mapping) and isinstance(part.get("text"), str):
                    fragments.append(part["text"])
            if event.get("type") == "error":
                error = event.get("error")
                if isinstance(error, Mapping) and isinstance(error.get("data"), Mapping):
                    provider_error = str(error["data"].get("message", "OpenCode provider error"))
                else:
                    provider_error = "OpenCode provider error"
        return fragments, session_id, provider_error

    @staticmethod
    def _safe_error(value: str) -> str:
        return " ".join(value.split())[:300]

    @staticmethod
    def _classify_error(message: str) -> OpenCodeRuntimeError:
        normalized = message.lower()
        if any(marker in normalized for marker in ("401", "authentication", "token refresh", "api key", "credential")):
            return OpenCodeAuthenticationError("OpenCode provider authentication is unavailable")
        return OpenCodeRuntimeError("OpenCode CLI request failed")
