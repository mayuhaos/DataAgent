"""Typed contract for recipe selection and execution."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


CONTRACT_VERSION = "1.0"
RECIPE_IDS = frozenset({"tabular_aggregate_v1", "dimension_variation_v1"})


class AnalysisContractError(ValueError):
    """Raised when an Agent proposal cannot safely enter the recipe library."""


@dataclass(frozen=True)
class AnalysisSpec:
    contract_version: str
    mode: str
    recipe_id: str | None
    recipe_version: str | None
    parameters: dict[str, Any]
    reason: str | None = None

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "AnalysisSpec":
        if not isinstance(value, Mapping):
            raise AnalysisContractError("AnalysisSpec must be an object")

        allowed = {
            "contract_version",
            "mode",
            "recipe_id",
            "recipe_version",
            "parameters",
            "reason",
        }
        unexpected = set(value) - allowed
        if unexpected:
            raise AnalysisContractError(
                "AnalysisSpec contains unsupported fields: " + ", ".join(sorted(unexpected))
            )

        contract_version = value.get("contract_version")
        if contract_version != CONTRACT_VERSION:
            raise AnalysisContractError(
                f"Unsupported contract_version: {contract_version!r}"
            )

        mode = value.get("mode")
        if mode not in {"recipe", "legacy"}:
            raise AnalysisContractError("mode must be recipe or legacy")

        recipe_id = value.get("recipe_id")
        recipe_version = value.get("recipe_version")
        parameters = value.get("parameters")
        reason = value.get("reason")

        if mode == "recipe":
            if recipe_id not in RECIPE_IDS:
                raise AnalysisContractError(f"Unknown recipe_id: {recipe_id!r}")
            if not isinstance(recipe_version, str) or not recipe_version:
                raise AnalysisContractError("recipe mode requires recipe_version")
            if not isinstance(parameters, Mapping):
                raise AnalysisContractError("recipe mode requires object parameters")
        else:
            if not isinstance(reason, str) or not reason.strip():
                raise AnalysisContractError("legacy mode requires a non-empty reason")
            if recipe_id is not None or recipe_version is not None:
                raise AnalysisContractError(
                    "legacy mode cannot include recipe_id or recipe_version"
                )
            if parameters not in (None, {}):
                raise AnalysisContractError("legacy mode cannot include parameters")
            parameters = {}

        return cls(
            contract_version=contract_version,
            mode=mode,
            recipe_id=recipe_id,
            recipe_version=recipe_version,
            parameters=dict(parameters),
            reason=reason,
        )

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "contract_version": self.contract_version,
            "mode": self.mode,
        }
        if self.mode == "recipe":
            result["parameters"] = self.parameters
        if self.recipe_id is not None:
            result["recipe_id"] = self.recipe_id
        if self.recipe_version is not None:
            result["recipe_version"] = self.recipe_version
        if self.reason is not None:
            result["reason"] = self.reason
        return result
