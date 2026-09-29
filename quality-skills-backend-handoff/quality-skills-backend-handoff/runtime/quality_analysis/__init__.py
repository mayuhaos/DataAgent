"""Versioned, allowlisted quality-analysis recipes for the Python Skill POC."""

from .contracts import AnalysisContractError, AnalysisSpec
from .launcher import render_launcher
from .opencode_runtime import OpenCodeSkillRuntime
from .report_facts import assess_dimension_report_facts, assess_spray_report_facts
from .recipes import execute_analysis
from .spray_cases import SPRAY_CASES, get_spray_case, validate_spray_case_spec

__all__ = [
    "AnalysisContractError",
    "AnalysisSpec",
    "OpenCodeSkillRuntime",
    "SPRAY_CASES",
    "assess_dimension_report_facts",
    "assess_spray_report_facts",
    "execute_analysis",
    "get_spray_case",
    "render_launcher",
    "validate_spray_case_spec",
]
