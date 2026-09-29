"""Typed, fact-grounded report generation for the quality-analysis POC."""

from .contracts import ChartSpec, ReportContractError, ReportDraft, ReportFacts
from .facts import build_dimension_report_facts, build_spray_report_facts
from .renderer import render_report

__all__ = [
    "ChartSpec",
    "ReportContractError",
    "ReportDraft",
    "ReportFacts",
    "build_dimension_report_facts",
    "build_spray_report_facts",
    "render_report",
]
