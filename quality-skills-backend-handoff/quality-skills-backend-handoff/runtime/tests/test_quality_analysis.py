from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from fixtures.sample_data import (
    TOP_POINT_RANGES,
    defect_top_spec,
    dimension_variation_rows,
    dimension_variation_spec,
    paint_quality_rows,
    weekly_yield_rows,
    weekly_yield_spec,
)
from quality_analysis import AnalysisContractError, execute_analysis, render_launcher
from quality_analysis.operators import json_safe


class AnalysisSpecContractTests(unittest.TestCase):
    def test_json_safe_serializes_database_decimal_values(self) -> None:
        result = json_safe({"rate": Decimal("98.75"), "missing": Decimal("NaN")})
        self.assertEqual(98.75, result["rate"])
        self.assertIsNone(result["missing"])
        json.dumps(result)

    def test_unknown_recipe_is_rejected(self) -> None:
        with self.assertRaises(AnalysisContractError):
            execute_analysis(
                {
                    "contract_version": "1.0",
                    "mode": "recipe",
                    "recipe_id": "made_up_recipe",
                    "recipe_version": "1.0",
                    "parameters": {},
                },
                [],
            )

    def test_legacy_request_does_not_execute_python(self) -> None:
        result = execute_analysis(
            {
                "contract_version": "1.0",
                "mode": "legacy",
                "parameters": {},
                "reason": "Causal diagnosis is not a registered recipe.",
            },
            [],
        )
        self.assertEqual("legacy_required", result["status"])
        self.assertIn("Causal diagnosis", result["reason"])

    def test_extra_contract_field_is_rejected(self) -> None:
        spec = dimension_variation_spec()
        spec["untrusted_instruction"] = "run arbitrary code"
        with self.assertRaises(AnalysisContractError):
            execute_analysis(spec, dimension_variation_rows())

    def test_unknown_recipe_parameter_is_rejected(self) -> None:
        spec = dimension_variation_spec()
        spec["parameters"]["untrusted_instruction"] = "run arbitrary code"
        with self.assertRaises(AnalysisContractError):
            execute_analysis(spec, dimension_variation_rows())


class RecipeTests(unittest.TestCase):
    def test_dimension_variation_matches_recorded_shape(self) -> None:
        result = execute_analysis(
            dimension_variation_spec(), dimension_variation_rows()
        )
        summary = result["analysis_summary"]
        self.assertEqual(42, summary["total_points_analyzed"])
        self.assertEqual(7, summary["candidate_points_count"])
        self.assertEqual(7, summary["high_variation_points_count"])

        selected = result["high_variation_points"]
        self.assertEqual(list(TOP_POINT_RANGES), [
            point["measurement_point"] for point in selected
        ])
        self.assertTrue(all(point["cpk"] is None for point in selected))
        self.assertTrue(
            all(not point["is_sample_reach_minimum_cpk"] for point in selected)
        )
        self.assertEqual(
            "significant_linear_trend",
            next(
                point["trend"]
                for point in selected
                if point["measurement_point"] == "P003"
            ),
        )

    def test_dimension_variation_calculates_cpk_after_sample_gate(self) -> None:
        result = execute_analysis(
            dimension_variation_spec(),
            dimension_variation_rows(point_count=1, samples_per_point=125),
        )
        point = result["high_variation_points"][0]
        self.assertTrue(point["is_sample_reach_minimum_cpk"])
        self.assertIsNotNone(point["cpk"])
        self.assertIsNotNone(point["ppk"])

    def test_weekly_yield_uses_total_numerator_and_denominator(self) -> None:
        result = execute_analysis(weekly_yield_spec(), weekly_yield_rows())
        self.assertEqual(3, result["row_count"])
        self.assertEqual("rising", result["trend"]["direction"])
        self.assertEqual(95.0, result["rows"][0]["yield_rate"])
        self.assertAlmostEqual(96.6666666667, result["rows"][1]["yield_rate"])
        self.assertEqual(97.5, result["rows"][2]["yield_rate"])

    def test_defect_top_three_uses_defect_fact_rows(self) -> None:
        result = execute_analysis(defect_top_spec(), paint_quality_rows())
        self.assertEqual(3, result["row_count"])
        self.assertEqual("particle", result["rows"][0]["defect_name"])
        self.assertEqual(20, result["rows"][0]["defect_total"])
        self.assertAlmostEqual(55.5555555556, result["rows"][0]["defect_share"])
        self.assertEqual("scratch", result["rows"][1]["defect_name"])


class LauncherIntegrationTests(unittest.TestCase):
    def test_rendered_launcher_consumes_json_and_emits_one_json_object(self) -> None:
        spec = dimension_variation_spec()
        launcher = render_launcher(spec)
        self.assertIn("ANALYSIS_SPEC = json.loads(", launcher)
        with tempfile.TemporaryDirectory() as directory:
            script_path = Path(directory) / "launcher.py"
            script_path.write_text(launcher, encoding="utf-8")
            environment = os.environ.copy()
            environment["PYTHONPATH"] = str(ROOT)
            completed = subprocess.run(
                [sys.executable, str(script_path)],
                input=json.dumps(dimension_variation_rows()).encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=environment,
                check=True,
            )
        result = json.loads(completed.stdout)
        self.assertEqual("success", result["status"])
        self.assertEqual(7, result["analysis_summary"]["candidate_points_count"])

    def test_skill_layout_is_complete(self) -> None:
        completed = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "verify_skill_layout.py")],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
            text=True,
        )
        self.assertIn("layout verified", completed.stdout)


if __name__ == "__main__":
    unittest.main()
