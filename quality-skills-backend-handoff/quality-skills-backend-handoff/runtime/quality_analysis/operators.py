"""Reusable, deterministic operators built on pandas and NumPy."""

from __future__ import annotations

from decimal import Decimal
from datetime import date, datetime
from typing import Any, Iterable, Sequence

import numpy as np
import pandas as pd

from .contracts import AnalysisContractError


def require_columns(frame: pd.DataFrame, columns: Iterable[str]) -> None:
    missing = [column for column in columns if column not in frame.columns]
    if missing:
        raise AnalysisContractError("Input rows are missing columns: " + ", ".join(missing))


def require_non_empty_list(value: Any, field_name: str) -> list[str]:
    if not isinstance(value, list) or not value or not all(
        isinstance(item, str) and item for item in value
    ):
        raise AnalysisContractError(f"{field_name} must be a non-empty list of names")
    return value


def numeric_series(series: pd.Series, field_name: str) -> pd.Series:
    converted = pd.to_numeric(series, errors="coerce")
    if converted.notna().sum() == 0:
        raise AnalysisContractError(f"{field_name} has no numeric values")
    return converted


def linear_slope(x_values: Sequence[Any], y_values: Sequence[Any]) -> float | None:
    x = pd.to_numeric(pd.Series(x_values), errors="coerce")
    y = pd.to_numeric(pd.Series(y_values), errors="coerce")
    valid = x.notna() & y.notna()
    if valid.sum() < 2:
        return None
    x = x[valid].to_numpy(dtype=float)
    y = y[valid].to_numpy(dtype=float)
    if np.isclose(np.std(x), 0.0):
        return None
    return float(np.polyfit(x, y, 1)[0])


def json_safe(value: Any) -> Any:
    if value is None or value is pd.NA or value is pd.NaT:
        return None
    if isinstance(value, (np.bool_, bool)):
        return bool(value)
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.floating):
        numeric = float(value)
        return None if not np.isfinite(numeric) else numeric
    if isinstance(value, Decimal):
        return float(value) if value.is_finite() else None
    if isinstance(value, np.ndarray):
        return [json_safe(item) for item in value.tolist()]
    if isinstance(value, (pd.Timestamp, datetime, date)):
        return value.isoformat()
    if isinstance(value, pd.Timedelta):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def records(frame: pd.DataFrame) -> list[dict[str, Any]]:
    return [json_safe(record) for record in frame.to_dict(orient="records")]
