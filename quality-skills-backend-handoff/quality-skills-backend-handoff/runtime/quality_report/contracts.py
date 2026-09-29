"""Strict contracts for fact-grounded quality reports and charts."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


REPORT_CONTRACT_VERSION = "1.0"
CHART_TYPES = frozenset({"line", "bar", "pie"})


class ReportContractError(ValueError):
    """Raised when a report draft cannot be safely rendered."""


def _object(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ReportContractError(f"{name} must be an object")
    return value


def _non_empty_string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ReportContractError(f"{name} must be a non-empty string")
    return value.strip()


@dataclass(frozen=True)
class ChartSpec:
    chart_type: str
    dataset_id: str
    x_field: str
    y_field: str
    title: str

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "ChartSpec":
        data = _object(value, "chart")
        allowed = {"chart_type", "dataset_id", "x_field", "y_field", "title"}
        unexpected = set(data) - allowed
        if unexpected:
            raise ReportContractError("chart has unsupported fields: " + ", ".join(sorted(unexpected)))
        chart_type = _non_empty_string(data.get("chart_type"), "chart.chart_type")
        if chart_type not in CHART_TYPES:
            raise ReportContractError("chart.chart_type is not supported")
        return cls(
            chart_type=chart_type,
            dataset_id=_non_empty_string(data.get("dataset_id"), "chart.dataset_id"),
            x_field=_non_empty_string(data.get("x_field"), "chart.x_field"),
            y_field=_non_empty_string(data.get("y_field"), "chart.y_field"),
            title=_non_empty_string(data.get("title"), "chart.title"),
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "chart_type": self.chart_type,
            "dataset_id": self.dataset_id,
            "x_field": self.x_field,
            "y_field": self.y_field,
            "title": self.title,
        }

    def matches_data_shape(self, requirement: "ChartSpec") -> bool:
        return (
            self.chart_type == requirement.chart_type
            and self.dataset_id == requirement.dataset_id
            and self.x_field == requirement.x_field
            and self.y_field == requirement.y_field
        )


@dataclass(frozen=True)
class ReportFacts:
    contract_version: str
    status: str
    case_id: str
    question: str
    scope: dict[str, Any]
    source_tables: list[str]
    metric_definition: str
    rows: list[dict[str, Any]]
    fact_ids: frozenset[str]
    chart_requirement: ChartSpec | None
    limitations: list[str]
    blocked_reason: str | None = None

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "ReportFacts":
        data = _object(value, "ReportFacts")
        allowed = {
            "contract_version",
            "status",
            "case_id",
            "question",
            "scope",
            "source_tables",
            "metric_definition",
            "rows",
            "fact_ids",
            "chart_requirement",
            "limitations",
            "blocked_reason",
        }
        unexpected = set(data) - allowed
        if unexpected:
            raise ReportContractError("ReportFacts has unsupported fields: " + ", ".join(sorted(unexpected)))
        if data.get("contract_version") != REPORT_CONTRACT_VERSION:
            raise ReportContractError("unsupported ReportFacts contract version")
        status = data.get("status")
        if status not in {"reportable", "blocked"}:
            raise ReportContractError("ReportFacts.status must be reportable or blocked")
        scope = _object(data.get("scope"), "scope")
        source_tables = data.get("source_tables")
        rows = data.get("rows")
        fact_ids = data.get("fact_ids")
        limitations = data.get("limitations")
        if not isinstance(source_tables, list) or not all(isinstance(item, str) and item for item in source_tables):
            raise ReportContractError("source_tables must be a non-empty list of strings")
        if not isinstance(rows, list) or not all(isinstance(item, Mapping) for item in rows):
            raise ReportContractError("rows must be a list of objects")
        if not isinstance(fact_ids, list) or not fact_ids or not all(
            isinstance(item, str) and item for item in fact_ids
        ):
            raise ReportContractError("fact_ids must be a non-empty list of strings")
        if not isinstance(limitations, list) or not all(isinstance(item, str) for item in limitations):
            raise ReportContractError("limitations must be a list of strings")

        requirement_data = data.get("chart_requirement")
        blocked_reason = data.get("blocked_reason")
        if status == "reportable":
            if not rows:
                raise ReportContractError("reportable ReportFacts must contain rows")
            if requirement_data is None:
                raise ReportContractError("reportable ReportFacts require a chart_requirement")
            chart_requirement = ChartSpec.from_mapping(_object(requirement_data, "chart_requirement"))
            if blocked_reason is not None:
                raise ReportContractError("reportable ReportFacts cannot contain blocked_reason")
        else:
            if requirement_data is not None:
                raise ReportContractError("blocked ReportFacts cannot contain a chart_requirement")
            if not isinstance(blocked_reason, str) or not blocked_reason.strip():
                raise ReportContractError("blocked ReportFacts require blocked_reason")
            chart_requirement = None

        return cls(
            contract_version=REPORT_CONTRACT_VERSION,
            status=status,
            case_id=_non_empty_string(data.get("case_id"), "case_id"),
            question=_non_empty_string(data.get("question"), "question"),
            scope=dict(scope),
            source_tables=list(source_tables),
            metric_definition=_non_empty_string(data.get("metric_definition"), "metric_definition"),
            rows=[dict(item) for item in rows],
            fact_ids=frozenset(fact_ids),
            chart_requirement=chart_requirement,
            limitations=list(limitations),
            blocked_reason=blocked_reason.strip() if isinstance(blocked_reason, str) else None,
        )

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "contract_version": self.contract_version,
            "status": self.status,
            "case_id": self.case_id,
            "question": self.question,
            "scope": self.scope,
            "source_tables": self.source_tables,
            "metric_definition": self.metric_definition,
            "rows": self.rows,
            "fact_ids": sorted(self.fact_ids),
            "limitations": self.limitations,
        }
        if self.chart_requirement:
            result["chart_requirement"] = self.chart_requirement.to_dict()
        if self.blocked_reason:
            result["blocked_reason"] = self.blocked_reason
        return result


@dataclass(frozen=True)
class ReportObservation:
    text: str
    fact_ids: list[str]

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any], allowed_fact_ids: frozenset[str]) -> "ReportObservation":
        data = _object(value, "observation")
        if set(data) - {"text", "fact_ids"}:
            raise ReportContractError("observation has unsupported fields")
        fact_ids = data.get("fact_ids")
        if not isinstance(fact_ids, list) or not fact_ids or not all(
            isinstance(item, str) and item in allowed_fact_ids for item in fact_ids
        ):
            raise ReportContractError("observation references an unknown fact")
        return cls(
            text=_non_empty_string(data.get("text"), "observation.text"),
            fact_ids=list(fact_ids),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"text": self.text, "fact_ids": self.fact_ids}


@dataclass(frozen=True)
class ReportDraft:
    contract_version: str
    status: str
    title: str
    executive_summary: list[ReportObservation]
    chart: ChartSpec
    limitations: list[str]

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any], facts: ReportFacts) -> "ReportDraft":
        data = _object(value, "ReportDraft")
        allowed = {"contract_version", "status", "title", "executive_summary", "chart", "limitations"}
        unexpected = set(data) - allowed
        if unexpected:
            raise ReportContractError("ReportDraft has unsupported fields: " + ", ".join(sorted(unexpected)))
        if facts.status != "reportable":
            raise ReportContractError("blocked facts cannot be converted into a ReportDraft")
        if data.get("contract_version") != REPORT_CONTRACT_VERSION:
            raise ReportContractError("unsupported ReportDraft contract version")
        if data.get("status") != "report":
            raise ReportContractError("ReportDraft.status must be report")
        summary = data.get("executive_summary")
        limitations = data.get("limitations")
        if not isinstance(summary, list) or not 1 <= len(summary) <= 4:
            raise ReportContractError("executive_summary must contain one to four observations")
        if not isinstance(limitations, list) or not all(isinstance(item, str) for item in limitations):
            raise ReportContractError("ReportDraft.limitations must be a list of strings")
        chart = ChartSpec.from_mapping(_object(data.get("chart"), "chart"))
        if not chart.matches_data_shape(facts.chart_requirement):
            raise ReportContractError("ReportDraft chart does not match the approved chart requirement")
        return cls(
            contract_version=REPORT_CONTRACT_VERSION,
            status="report",
            title=_non_empty_string(data.get("title"), "title"),
            executive_summary=[
                ReportObservation.from_mapping(_object(item, "observation"), facts.fact_ids)
                for item in summary
            ],
            chart=chart,
            limitations=list(limitations),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "contract_version": self.contract_version,
            "status": self.status,
            "title": self.title,
            "executive_summary": [item.to_dict() for item in self.executive_summary],
            "chart": self.chart.to_dict(),
            "limitations": self.limitations,
        }
