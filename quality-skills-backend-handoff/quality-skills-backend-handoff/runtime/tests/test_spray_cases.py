from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_analysis import (
    AnalysisContractError,
    AnalysisSpec,
    assess_dimension_report_facts,
    assess_spray_report_facts,
    get_spray_case,
    validate_spray_case_spec,
)
from quality_analysis.spray_cases import source_metric_values


class SprayCaseContractTests(unittest.TestCase):
    def test_every_case_has_an_approved_tabular_contract(self):
        for case_id in (
            "weekly_first_pass_rate",
            "defect_top3_share",
            "three_defect_categories",
            "shift_defect_rate",
            "source_overall_pass_rate",
        ):
            case = get_spray_case(case_id)
            spec = AnalysisSpec.from_mapping(
                {
                    "contract_version": "1.0",
                    "mode": "recipe",
                    "recipe_id": "tabular_aggregate_v1",
                    "recipe_version": "1.0",
                    "parameters": case.expected_parameters,
                }
            )
            validate_spray_case_spec(case, spec)

    def test_case_rejects_an_unapproved_parameter_change(self):
        case = get_spray_case("defect_top3_share")
        parameters = dict(case.expected_parameters)
        parameters["limit"] = 10
        spec = AnalysisSpec.from_mapping(
            {
                "contract_version": "1.0",
                "mode": "recipe",
                "recipe_id": "tabular_aggregate_v1",
                "recipe_version": "1.0",
                "parameters": parameters,
            }
        )
        with self.assertRaises(AnalysisContractError):
            validate_spray_case_spec(case, spec)

    def test_case_accepts_empty_optional_derived_list(self):
        case = get_spray_case("three_defect_categories")
        parameters = dict(case.expected_parameters)
        parameters["derived"] = []
        spec = AnalysisSpec.from_mapping(
            {
                "contract_version": "1.0",
                "mode": "recipe",
                "recipe_id": "tabular_aggregate_v1",
                "recipe_version": "1.0",
                "parameters": parameters,
            }
        )
        validate_spray_case_spec(case, spec)

    def test_source_owned_metric_detects_conflicting_values(self):
        case = get_spray_case("source_overall_pass_rate")
        values = source_metric_values(
            case,
            [
                {"batch_id": 1, "overall_pass_rate": 98.1},
                {"batch_id": 2, "overall_pass_rate": 97.9},
            ],
        )
        self.assertEqual([97.9, 98.1], values)


class ReportFactAssessmentTests(unittest.TestCase):
    def test_dimension_analysis_with_points_is_reportable(self):
        assessment = assess_dimension_report_facts(
            {
                "analysis_summary": {
                    "total_points_analyzed": 42,
                    "high_variation_points_count": 7,
                },
                "high_variation_points": [{"measurement_point": "P1"}],
            },
            factory_id=1,
            measurement_date="2026-09-14",
            part_name="demo",
        )
        self.assertEqual("reportable", assessment["status"])

    def test_weekly_result_with_trend_facts_is_reportable(self):
        assessment = assess_spray_report_facts(
            get_spray_case("weekly_first_pass_rate"),
            {"status": "success", "rows": [{"week": "2026-W23"}, {"week": "2026-W24"}]},
            factory_id=1,
            start_date="2026-06-01",
            end_date="2026-07-01",
        )
        self.assertEqual("reportable", assessment["status"])

    def test_source_metric_conflict_blocks_report_generation(self):
        assessment = assess_spray_report_facts(
            get_spray_case("source_overall_pass_rate"),
            None,
            factory_id=1,
            start_date="2026-06-01",
            end_date="2026-07-01",
            source_metric_conflict=[97.9, 98.1],
        )
        self.assertEqual("blocked", assessment["status"])
        self.assertEqual("source_metric_conflict", assessment["reason"])
        self.assertEqual(2, assessment["distinct_source_metric_count"])


if __name__ == "__main__":
    unittest.main()
