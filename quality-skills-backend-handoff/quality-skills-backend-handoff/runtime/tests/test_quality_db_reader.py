from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quality_db.reader import (
    QualityDbAccessError,
    QualityDbConfigurationError,
    QualityDbReader,
)


SAFE_URL = "mysql+pymysql://quality_poc_reader:secret@db.example:3307/data2sql_zhijian"


class FakeResult:
    def __init__(self, scalar=None, rows=None):
        self._scalar = scalar
        self._rows = rows or []

    def scalar_one(self):
        return self._scalar

    def mappings(self):
        return self._rows

    def __iter__(self):
        return iter(self._rows)


class FakeConnection:
    def __init__(self, grants=None):
        self.grants = grants or [
            ("GRANT USAGE ON *.* TO quality_poc_reader",),
            ("GRANT SELECT, SHOW VIEW ON data2sql_zhijian.* TO quality_poc_reader",),
        ]
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def execute(self, statement, parameters=None):
        sql = str(statement)
        self.calls.append((sql, dict(parameters or {})))
        if sql == "SELECT VERSION()":
            return FakeResult(scalar="8.0.fake")
        if sql == "SHOW GRANTS":
            return FakeResult(rows=self.grants)
        if "information_schema.tables" in sql:
            return FakeResult(rows=[{"table_name": "dimension_measurement"}])
        if "FROM dimension_measurement" in sql:
            return FakeResult(
                rows=[
                    {
                        "part_name": "demo",
                        "measurement_point": "P1",
                        "sample_no": 1,
                        "value": 0.1,
                        "lower_limit": -0.75,
                        "upper_limit": 0.75,
                    }
                ]
            )
        return FakeResult(rows=[])


class FakeEngine:
    def __init__(self, connection):
        self.connection = connection

    def connect(self):
        return self.connection


class OverLimitSprayConnection(FakeConnection):
    def execute(self, statement, parameters=None):
        sql = str(statement)
        if "FROM paint_product_shift_stats" in sql:
            self.calls.append((sql, dict(parameters or {})))
            return FakeResult(rows=[{"shift_name": "日"}] * 51)
        return super().execute(statement, parameters)


class QualityDbReaderTests(unittest.TestCase):
    def test_root_url_is_rejected_before_engine_creation(self):
        with self.assertRaises(QualityDbConfigurationError):
            QualityDbReader(
                "mysql+pymysql://root:secret@db.example:3307/data2sql_zhijian"
            )

    def test_connection_preflight_accepts_narrow_read_only_grants(self):
        connection = FakeConnection()
        reader = QualityDbReader(SAFE_URL, FakeEngine(connection))
        result = reader.check_connection()
        self.assertEqual("8.0.fake", result.server_version)
        self.assertTrue(result.account_is_read_only)
        self.assertEqual("connection_preflight", reader.audit_events[0].template_id)

    def test_connection_preflight_rejects_write_grant(self):
        connection = FakeConnection(
            grants=[
                ("GRANT USAGE ON *.* TO quality_poc_reader",),
                ("GRANT SELECT, INSERT ON data2sql_zhijian.* TO quality_poc_reader",),
            ]
        )
        reader = QualityDbReader(SAFE_URL, FakeEngine(connection))
        with self.assertRaises(QualityDbAccessError):
            reader.check_connection()

    def test_metadata_field_read_is_case_insensitive(self):
        self.assertEqual("dimension_measurement", QualityDbReader._field(
            {"TABLE_NAME": "dimension_measurement"}, "table_name"
        ))

    def test_registered_template_binds_values_and_does_not_interpolate_sql(self):
        connection = FakeConnection()
        reader = QualityDbReader(SAFE_URL, FakeEngine(connection))
        part_name = "part'; DROP TABLE dimension_measurement; --"
        rows = reader.execute_template(
            "dimension_variation_source_v1",
            {
                "factory_id": 1,
                "measurement_date": "2026-09-14",
                "part_name": part_name,
                "limit": 50,
            },
        )
        statement, parameters = connection.calls[-1]
        self.assertIn(":part_name", statement)
        self.assertNotIn(part_name, statement)
        self.assertEqual(part_name, parameters["part_name"])
        self.assertEqual(1, len(rows))

    def test_unknown_template_is_rejected(self):
        reader = QualityDbReader(SAFE_URL, FakeEngine(FakeConnection()))
        with self.assertRaises(QualityDbAccessError):
            reader.execute_template("free_form_sql", {})

    def test_template_rejects_row_limit_above_cap(self):
        reader = QualityDbReader(SAFE_URL, FakeEngine(FakeConnection()))
        with self.assertRaises(QualityDbAccessError):
            reader.execute_template(
                "dimension_variation_source_v1",
                {
                    "factory_id": 1,
                    "measurement_date": "2026-09-14",
                    "part_name": "demo",
                    "limit": 501,
                },
            )

    def test_spray_template_uses_bound_factory_and_period_parameters(self):
        connection = FakeConnection()
        reader = QualityDbReader(SAFE_URL, FakeEngine(connection))
        reader.execute_template(
            "spray_shift_defect_rate_source_v1",
            {
                "factory_id": 1,
                "start_date": "2026-06-01",
                "end_date": "2026-07-01",
                "limit": 50,
            },
        )
        statement, parameters = connection.calls[-1]
        self.assertIn("FROM paint_product_shift_stats", statement)
        self.assertIn(":start_date", statement)
        self.assertIn(":end_date", statement)
        self.assertIn("GROUP BY CASE p.shift_name", statement)
        self.assertIn("WHEN '日' THEN '白班'", statement)
        self.assertEqual("2026-06-01", parameters["start_date"])
        self.assertEqual("2026-07-01", parameters["end_date"])
        self.assertEqual(51, parameters["fetch_limit"])

    def test_spray_template_rejects_invalid_period(self):
        reader = QualityDbReader(SAFE_URL, FakeEngine(FakeConnection()))
        with self.assertRaises(QualityDbAccessError):
            reader.execute_template(
                "spray_shift_defect_rate_source_v1",
                {
                    "factory_id": 1,
                    "start_date": "2026-07-01",
                    "end_date": "2026-06-01",
                },
            )

    def test_spray_template_enforces_requested_row_limit(self):
        reader = QualityDbReader(SAFE_URL, FakeEngine(OverLimitSprayConnection()))
        with self.assertRaises(QualityDbAccessError):
            reader.execute_template(
                "spray_shift_defect_rate_source_v1",
                {
                    "factory_id": 1,
                    "start_date": "2026-06-01",
                    "end_date": "2026-07-01",
                    "limit": 50,
                },
            )


if __name__ == "__main__":
    unittest.main()
