"""Bounded, read-only QUALITY_DB access for the POC."""

from __future__ import annotations

import hashlib
import os
import time
from dataclasses import dataclass
from datetime import date
from typing import Any, Mapping

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine, URL, make_url


class QualityDbConfigurationError(ValueError):
    """Raised before a connection when environment configuration is unsafe."""


class QualityDbAccessError(RuntimeError):
    """Raised for a rejected template or an unsafe database response."""


@dataclass(frozen=True)
class AuditEvent:
    purpose: str
    template_id: str
    row_count: int
    elapsed_ms: int
    result_hash: str


@dataclass(frozen=True)
class ConnectionInfo:
    server_version: str
    account_is_read_only: bool


class QualityDbReader:
    """Owns secrets, allowlisted templates, result bounds, and redacted audit data."""

    DATABASE_NAME = "data2sql_zhijian"
    MAX_ROWS = 500
    WRITE_PRIVILEGES = (
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "REFERENCES", "INDEX", "GRANT OPTION", "ALL PRIVILEGES",
    )

    def __init__(self, database_url: str, engine: Engine | None = None) -> None:
        self._url = self._validate_url(database_url)
        self._engine = engine or create_engine(
            self._url,
            pool_pre_ping=True,
            pool_recycle=1800,
            connect_args={"connect_timeout": 10, "read_timeout": 30, "write_timeout": 30},
        )
        self.audit_events: list[AuditEvent] = []

    @classmethod
    def from_environment(cls) -> "QualityDbReader":
        value = os.environ.get("QUALITY_DB_URL")
        if not value:
            raise QualityDbConfigurationError("QUALITY_DB_URL is not configured")
        return cls(value)

    @classmethod
    def _validate_url(cls, database_url: str) -> URL:
        try:
            parsed = make_url(database_url)
        except Exception as exc:
            raise QualityDbConfigurationError("QUALITY_DB_URL is malformed") from exc
        if parsed.drivername != "mysql+pymysql":
            raise QualityDbConfigurationError("QUALITY_DB_URL must use mysql+pymysql")
        if parsed.database != cls.DATABASE_NAME:
            raise QualityDbConfigurationError("QUALITY_DB_URL targets an unexpected database")
        if not parsed.username:
            raise QualityDbConfigurationError("QUALITY_DB_URL requires a username")
        if parsed.username.lower() == "root":
            raise QualityDbConfigurationError("root is prohibited for the POC")
        if not parsed.host or not parsed.port or parsed.password is None:
            raise QualityDbConfigurationError("QUALITY_DB_URL is incomplete")
        return parsed

    def check_connection(self, purpose: str = "connection_preflight") -> ConnectionInfo:
        started = time.monotonic()
        with self._engine.connect() as connection:
            server_version = str(connection.execute(text("SELECT VERSION()")).scalar_one())
            grants = [str(row[0]) for row in connection.execute(text("SHOW GRANTS"))]
        read_only = self._grants_are_read_only(grants)
        self._audit(purpose, "connection_preflight", 0, started, [server_version, read_only])
        if not read_only:
            raise QualityDbAccessError("database account has write or administrative privileges")
        return ConnectionInfo(server_version=server_version, account_is_read_only=True)

    def list_tables(self, purpose: str = "metadata_list_tables") -> list[str]:
        statement = text(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema = :database_name ORDER BY table_name LIMIT :limit"
        )
        rows = self._execute_rows(
            statement,
            {"database_name": self.DATABASE_NAME, "limit": 100},
            purpose,
            "metadata_list_tables_v1",
        )
        return [str(self._field(row, "table_name")) for row in rows]

    def describe_table(self, table_name: str, purpose: str = "metadata_describe_table") -> list[dict[str, Any]]:
        if not isinstance(table_name, str) or not table_name:
            raise QualityDbAccessError("table_name is required")
        statement = text(
            "SELECT column_name, data_type, is_nullable, column_key, column_comment "
            "FROM information_schema.columns "
            "WHERE table_schema = :database_name AND table_name = :table_name "
            "ORDER BY ordinal_position LIMIT :limit"
        )
        return self._execute_rows(
            statement,
            {"database_name": self.DATABASE_NAME, "table_name": table_name, "limit": 200},
            purpose,
            "metadata_describe_table_v1",
        )

    def execute_template(
        self, template_id: str, parameters: Mapping[str, Any], purpose: str = "quality_analysis"
    ) -> list[dict[str, Any]]:
        if template_id == "dimension_variation_source_v1":
            values = self._validate_dimension_parameters(parameters)
            statement = text(
                "SELECT ir.part_name, dm.measurement_point, dm.sample_no, dm.value, "
                "tc.lower_limit, tc.upper_limit "
                "FROM dimension_measurement dm "
                "INNER JOIN inspection_report ir ON dm.report_id = ir.id "
                "INNER JOIN tolerance_catalog tc ON dm.tolerance_id = tc.id "
                "WHERE dm.factory_id = :factory_id "
                "AND ir.factory_id = :factory_id "
                "AND ir.measurement_date = :measurement_date "
                "AND ir.part_name = :part_name "
                "ORDER BY dm.measurement_point, dm.sample_no "
                "LIMIT :limit"
            )
        else:
            statement = self._spray_statement(template_id)
            values = self._validate_spray_period_parameters(parameters)
        row_limit = (
            values["fetch_limit"] - 1
            if template_id != "dimension_variation_source_v1"
            else self.MAX_ROWS
        )
        return self._execute_rows(
            statement, values, purpose, template_id, maximum_rows=row_limit
        )

    @staticmethod
    def _spray_statement(template_id: str) -> Any:
        statements = {
            "spray_weekly_first_pass_source_v1": (
                "SELECT DATE_FORMAT(p.stat_date, '%x-W%v') AS week, "
                "SUM(p.check_total) AS check_total, "
                "SUM(p.first_pass_count) AS first_pass_count "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date "
                "GROUP BY DATE_FORMAT(p.stat_date, '%x-W%v') "
                "ORDER BY week LIMIT :fetch_limit"
            ),
            "spray_defect_top_source_v1": (
                "SELECT d.defect_name, SUM(dd.defect_count) AS defect_count "
                "FROM paint_defect_detail dd "
                "INNER JOIN paint_defect_dictionary d ON d.id = dd.defect_id "
                "WHERE dd.factory_id = :factory_id "
                "AND dd.stat_date >= :start_date AND dd.stat_date < :end_date "
                "GROUP BY d.defect_name "
                "ORDER BY defect_count DESC, d.defect_name ASC LIMIT :fetch_limit"
            ),
            "spray_three_defect_categories_source_v1": (
                "SELECT defect_category, defect_count FROM ("
                "SELECT '塑件缺陷' AS defect_category, "
                "SUM(COALESCE(p.plastic_defect_count, 0)) AS defect_count "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date "
                "UNION ALL "
                "SELECT '喷房缺陷' AS defect_category, "
                "SUM(COALESCE(p.paint_room_defect_count, 0)) AS defect_count "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date "
                "UNION ALL "
                "SELECT '设备环境' AS defect_category, "
                "SUM(COALESCE(p.environment_defect_count, 0)) AS defect_count "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date"
                ") AS spray_category_rows LIMIT :fetch_limit"
            ),
            "spray_shift_defect_rate_source_v1": (
                "SELECT CASE p.shift_name "
                "WHEN '日' THEN '白班' WHEN '夜' THEN '夜班' ELSE p.shift_name END AS shift_name, "
                "SUM(p.ng_total) AS ng_total, "
                "SUM(p.check_total) AS check_total "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date "
                "GROUP BY CASE p.shift_name "
                "WHEN '日' THEN '白班' WHEN '夜' THEN '夜班' ELSE p.shift_name END "
                "ORDER BY p.shift_name LIMIT :fetch_limit"
            ),
            "spray_overall_pass_rate_source_v1": (
                "SELECT p.overall_pass_rate, COUNT(*) AS source_batch_count "
                "FROM paint_product_shift_stats p "
                "WHERE p.factory_id = :factory_id "
                "AND p.stat_date >= :start_date AND p.stat_date < :end_date "
                "GROUP BY p.overall_pass_rate "
                "ORDER BY p.overall_pass_rate LIMIT :fetch_limit"
            ),
        }
        try:
            return text(statements[template_id])
        except KeyError as exc:
            raise QualityDbAccessError(f"unregistered query template: {template_id}") from exc

    def _execute_rows(
        self,
        statement: Any,
        parameters: Mapping[str, Any],
        purpose: str,
        template_id: str,
        maximum_rows: int | None = None,
    ) -> list[dict[str, Any]]:
        started = time.monotonic()
        try:
            with self._engine.connect() as connection:
                rows = [dict(row) for row in connection.execute(statement, parameters).mappings()]
        except Exception as exc:
            raise QualityDbAccessError(f"database query failed: {exc.__class__.__name__}") from exc
        cap = self.MAX_ROWS if maximum_rows is None else maximum_rows
        if len(rows) > cap:
            raise QualityDbAccessError("query exceeded the POC row limit")
        self._audit(purpose, template_id, len(rows), started, rows)
        return rows

    def _validate_dimension_parameters(self, parameters: Mapping[str, Any]) -> dict[str, Any]:
        required = {"factory_id", "measurement_date", "part_name"}
        if set(parameters) - (required | {"limit"}):
            raise QualityDbAccessError("dimension template has unsupported parameters")
        if not required.issubset(parameters):
            raise QualityDbAccessError("dimension template has missing parameters")
        factory_id = parameters["factory_id"]
        measurement_date = parameters["measurement_date"]
        part_name = parameters["part_name"]
        limit = parameters.get("limit", self.MAX_ROWS)
        if not isinstance(factory_id, int) or factory_id < 1:
            raise QualityDbAccessError("factory_id must be a positive integer")
        if not isinstance(measurement_date, (date, str)):
            raise QualityDbAccessError("measurement_date must be an ISO date or date object")
        if not isinstance(part_name, str) or not part_name.strip() or len(part_name) > 255:
            raise QualityDbAccessError("part_name must be a non-empty string up to 255 characters")
        if not isinstance(limit, int) or not 1 <= limit <= self.MAX_ROWS:
            raise QualityDbAccessError(f"limit must be between 1 and {self.MAX_ROWS}")
        return {
            "factory_id": factory_id,
            "measurement_date": measurement_date,
            "part_name": part_name,
            "limit": limit,
        }

    def _validate_spray_period_parameters(
        self, parameters: Mapping[str, Any]
    ) -> dict[str, Any]:
        required = {"factory_id", "start_date", "end_date"}
        if set(parameters) - (required | {"limit"}):
            raise QualityDbAccessError("spray template has unsupported parameters")
        if not required.issubset(parameters):
            raise QualityDbAccessError("spray template has missing parameters")
        factory_id = parameters["factory_id"]
        if not isinstance(factory_id, int) or factory_id < 1:
            raise QualityDbAccessError("factory_id must be a positive integer")
        start_date = self._as_iso_date(parameters["start_date"], "start_date")
        end_date = self._as_iso_date(parameters["end_date"], "end_date")
        if start_date >= end_date:
            raise QualityDbAccessError("start_date must be before end_date")
        limit = parameters.get("limit", self.MAX_ROWS)
        if not isinstance(limit, int) or not 1 <= limit <= self.MAX_ROWS:
            raise QualityDbAccessError(f"limit must be between 1 and {self.MAX_ROWS}")
        return {
            "factory_id": factory_id,
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "fetch_limit": limit + 1,
        }

    @staticmethod
    def _as_iso_date(value: Any, name: str) -> date:
        if isinstance(value, date):
            return value
        if isinstance(value, str):
            try:
                return date.fromisoformat(value)
            except ValueError as exc:
                raise QualityDbAccessError(f"{name} must be an ISO date") from exc
        raise QualityDbAccessError(f"{name} must be an ISO date")

    def _grants_are_read_only(self, grants: list[str]) -> bool:
        if not grants:
            return False
        allowed_privileges = {"USAGE", "SELECT", "SHOW VIEW"}
        expected_schema = self.DATABASE_NAME.upper() + "."
        for grant in grants:
            normalized = grant.upper().replace("`", "")
            if not normalized.startswith("GRANT ") or " ON " not in normalized:
                return False
            privilege_text, target_text = normalized[6:].split(" ON ", 1)
            target = target_text.split(" TO ", 1)[0].strip()
            privileges = {item.strip() for item in privilege_text.split(",")}
            if not privileges.issubset(allowed_privileges):
                return False
            if privileges == {"USAGE"} and target == "*.*":
                continue
            if not target.startswith(expected_schema):
                return False
        return True

    @staticmethod
    def _field(row: Mapping[str, Any], name: str) -> Any:
        for key, value in row.items():
            if str(key).lower() == name.lower():
                return value
        raise QualityDbAccessError(f"database metadata is missing field: {name}")

    def _audit(
        self, purpose: str, template_id: str, row_count: int, started: float, result: Any
    ) -> None:
        digest = hashlib.sha256(repr(result).encode("utf-8")).hexdigest()
        self.audit_events.append(
            AuditEvent(
                purpose=purpose,
                template_id=template_id,
                row_count=row_count,
                elapsed_ms=round((time.monotonic() - started) * 1000),
                result_hash=digest,
            )
        )
